package io.zeebe.hazelcast.exporter;

import java.util.Optional;

public class ExporterConfiguration {

  private static final String ENV_PREFIX = "ZEEBE_HAZELCAST_";

  private int port = 5701;

  private String remoteAddress;

  private String name = "zeebe";

  private int capacity = -1;
  private int timeToLiveInSeconds = 0;

  private String format = "protobuf";

  private String enabledValueTypes = "";
  private String enabledRecordTypes = "";

  /**
   * 指定 members
   */
  private String members;

  public int getPort() {
    return getEnv("PORT").map(Integer::parseInt).orElse(port);
  }

  public String getName() {
    return getEnv("NAME").orElse(name);
  }

  public int getCapacity() {
    return getEnv("CAPACITY").map(Integer::parseInt).orElse(capacity);
  }

  public int getTimeToLiveInSeconds() {
    return getEnv("TIME_TO_LIVE_IN_SECONDS").map(Integer::parseInt).orElse(timeToLiveInSeconds);
  }

  public String getFormat() {
    return getEnv("FORMAT").orElse(format);
  }

  public String getEnabledValueTypes() {
    return getEnv("ENABLED_VALUE_TYPES").orElse(enabledValueTypes);
  }

  public String getEnabledRecordTypes() {
    return getEnv("ENABLED_RECORD_TYPES").orElse(enabledRecordTypes);
  }

  public String getMembers() {
    return members;
  }

  public void setMembers(String members) {
    this.members = members;
  }

  public Optional<String> getRemoteAddress() {
    Optional<String> env = getEnv("REMOTE_ADDRESS");
    Optional<String> optional = env.isPresent() ? env : Optional.ofNullable(remoteAddress);
    return optional.filter(remoteAddress -> !remoteAddress.isEmpty());
  }

  private Optional<String> getEnv(String name) {
    return Optional.ofNullable(System.getenv(ENV_PREFIX + name));
  }

  @Override
  public String toString() {
    return "[port="
            + port
            + ", remoteAddress="
            + remoteAddress
            + ", name="
            + name
            + ", enabledValueTypes="
            + enabledValueTypes
            + ", enabledRecordTypes="
            + enabledRecordTypes
            + ", capacity="
            + capacity
            + ", timeToLiveInSeconds="
            + timeToLiveInSeconds
            + ", format="
            + format
            + ", members="
            + members
            + "]";
  }
}
