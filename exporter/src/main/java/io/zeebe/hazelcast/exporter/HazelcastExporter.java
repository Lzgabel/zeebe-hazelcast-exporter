package io.zeebe.hazelcast.exporter;

import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.*;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.ringbuffer.Ringbuffer;
import io.camunda.zeebe.exporter.api.Exporter;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.exporter.api.context.Controller;
import io.camunda.zeebe.protocol.record.Record;
import io.zeebe.exporter.proto.RecordTransformer;
import io.zeebe.exporter.proto.Schema;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.function.Function;

public class HazelcastExporter implements Exporter {

  private static final Splitter COMA = Splitter.on(',').trimResults();

  private ExporterConfiguration config;
  private Logger logger;
  private Controller controller;

  private HazelcastInstance hazelcast;
  private Ringbuffer<byte[]> ringbuffer;

  private Function<Record, byte[]> recordTransformer;

  @Override
  public void configure(Context context) {
    logger = context.getLogger();
    config = context.getConfiguration().instantiate(ExporterConfiguration.class);

    logger.debug("Starting exporter with configuration: {}", config);

    final var filter = new HazelcastRecordFilter(config);
    context.setFilter(filter);

    configureFormat();
  }

  private void configureFormat() {
    final var format = config.getFormat();
    if (format.equalsIgnoreCase("protobuf")) {
      recordTransformer = this::recordToProtobuf;

    } else if (format.equalsIgnoreCase("json")) {
      recordTransformer = this::recordToJson;

    } else {
      throw new IllegalArgumentException(
          String.format(
              "Expected the parameter 'format' to be one fo 'protobuf' or 'json' but was '%s'",
              format));
    }
  }

  @Override
  public void open(Controller controller) {
    this.controller = controller;

    hazelcast =
        config
            .getRemoteAddress()
            .map(this::connectToHazelcast)
            .orElseGet(this::createHazelcastInstance);

    ringbuffer = hazelcast.getRingbuffer(config.getName());
    if (ringbuffer == null) {
      throw new IllegalStateException(
          String.format("Failed to open ring-buffer with name '%s'", config.getName()));
    }

    logger.info(
        "Export records to ring-buffer with name '{}' [head: {}, tail: {}, size: {}, capacity: {}]",
        ringbuffer.getName(),
        ringbuffer.headSequence(),
        ringbuffer.tailSequence(),
        ringbuffer.size(),
        ringbuffer.capacity());
  }

  private HazelcastInstance createHazelcastInstance() {
    final var port = this.config.getPort();

    final var hzConfig = new Config();
    hzConfig.getNetworkConfig().setPort(port);

    final NetworkConfig network = hzConfig.getNetworkConfig();

    // 关闭广播模式
    final JoinConfig join = network.getJoin();
    final MulticastConfig multicast = join.getMulticastConfig();
    multicast.setEnabled(false);

    // 设置固定 ip
    final TcpIpConfig tcpIp = join.getTcpIpConfig();
    tcpIp.setEnabled(true);
    for(final String member : COMA.splitToList(config.getMembers())) {
      InetAddress[] addresses = null;
      try {
        addresses = MoreObjects.firstNonNull(
                InetAddress.getAllByName(member),
                new InetAddress[0]);
      } catch (UnknownHostException e) {
        logger.error("[Hazelcast] Init Error, member={}, e={}", member, e);
      }

      if (Objects.nonNull(addresses)) {
        for (final InetAddress addr : addresses) {
          final String hostAddress = addr.getHostAddress();
          tcpIp.addMember(hostAddress);
          logger.info("[Hazelcast] New Member: " + hostAddress);
        }
      }
    }


    hzConfig.setProperty("hazelcast.logging.type", "slf4j");

    final var ringbufferConfig = new RingbufferConfig(this.config.getName());

    if (this.config.getCapacity() > 0) {
      ringbufferConfig.setCapacity(this.config.getCapacity());
    }
    if (this.config.getTimeToLiveInSeconds() > 0) {
      ringbufferConfig.setTimeToLiveSeconds(this.config.getTimeToLiveInSeconds());
    }

    hzConfig.addRingBufferConfig(ringbufferConfig);

    logger.info("Creating new in-memory Hazelcast instance [port: {}]", port);

    return Hazelcast.newHazelcastInstance(hzConfig);
  }

  private HazelcastInstance connectToHazelcast(String remoteAddress) {

    final var clientConfig = new ClientConfig();
    clientConfig.setProperty("hazelcast.logging.type", "slf4j");

    final var networkConfig = clientConfig.getNetworkConfig();
    networkConfig.addAddress(remoteAddress);

    logger.info("Connecting to remote Hazelcast instance [address: {}]", remoteAddress);

    return HazelcastClient.newHazelcastClient(clientConfig);
  }

  @Override
  public void close() {
    hazelcast.shutdown();
  }

  @Override
  public void export(Record record) {

    if (ringbuffer != null) {
      final byte[] transformedRecord = recordTransformer.apply(record);

      final var sequenceNumber = ringbuffer.add(transformedRecord);
      logger.trace(
          "Added a record to the ring-buffer [record-position: {}, ring-buffer sequence-number: {}]",
          record.getPosition(),
          sequenceNumber);
    }

    controller.updateLastExportedRecordPosition(record.getPosition());
  }

  private byte[] recordToProtobuf(Record record) {
    final Schema.Record dto = RecordTransformer.toGenericRecord(record);
    return dto.toByteArray();
  }

  private byte[] recordToJson(Record record) {
    final var json = record.toJson();
    return json.getBytes();
  }
}
