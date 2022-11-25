/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.TestInfo;

import io.kroxylicious.cluster.KafkaClusterConfig.KafkaEndpoints.Endpoint;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.ToString;

@Builder(toBuilder = true)
@Getter
@ToString
public class KafkaClusterConfig {

    private static final System.Logger LOGGER = System.getLogger(KafkaClusterConfig.class.getName());

    private TestInfo testInfo;

    /**
     * specifies the cluster execution mode.
     */
    private final KafkaClusterExecutionMode execMode;
    /**
     * if true, the cluster will be brought up in Kraft-mode
     */
    private final Boolean kraftMode;

    /**
     * name of SASL mechanism to be configured on kafka for the external listener, if null, anonymous communication
     * will be used.
     */
    private final String saslMechanism;
    @Builder.Default
    private Integer brokersNum = 1;

    @Builder.Default
    private Integer kraftControllers = 1;

    @Builder.Default
    private String kafkaKraftClusterId = Uuid.randomUuid().toString();
    /**
     * The users and passwords to be configured into the server's JAAS configuration used for the external listener.
     */
    @Singular
    private final Map<String, String> users;

    @Singular
    private final Map<String, String> brokerConfigs;

    public Stream<ConfigHolder> getBrokerConfigs(Supplier<KafkaEndpoints> endPointConfigSupplier, Supplier<Endpoint> zookeeperEndpointSupplier) {
        List<ConfigHolder> properties = new ArrayList<>();
        KafkaEndpoints kafkaEndpoints = endPointConfigSupplier.get();
        for (int brokerNum = 0; brokerNum < brokersNum; brokerNum++) {
            Properties server = new Properties();
            server.putAll(brokerConfigs);

            putConfig(server, "broker.id", Integer.toString(brokerNum));

            var interBrokerEndpoint = kafkaEndpoints.getInterBrokerEndpoint(brokerNum);
            var clientEndpoint = kafkaEndpoints.getClientEndpoint(brokerNum);

            // - EXTERNAL: used for communications to/from consumers/producers
            // - INTERNAL: used for inter-broker communications (always no auth)
            // - CONTROLLER: used for inter-broker controller communications (kraft - always no auth)

            var externalListenerTransport = saslMechanism == null ? "PLAINTEXT" : "SASL_PLAINTEXT";

            var protocolMap = new TreeMap<>();
            var listeners = new TreeMap<>();
            var advertisedListeners = new TreeMap<>();
            protocolMap.put("EXTERNAL", externalListenerTransport);
            listeners.put("EXTERNAL", clientEndpoint.getBind().toString());
            advertisedListeners.put("EXTERNAL", clientEndpoint.getConnect().toString());

            protocolMap.put("INTERNAL", "PLAINTEXT");
            listeners.put("INTERNAL", interBrokerEndpoint.getBind().toString());
            advertisedListeners.put("INTERNAL", interBrokerEndpoint.getConnect().toString());
            putConfig(server, "inter.broker.listener.name", "INTERNAL");

            if (isKraftMode()) {
                putConfig(server, "node.id", Integer.toString(brokerNum)); // Required by Kafka 3.3 onwards.

                var controllerEndpoint = kafkaEndpoints.getControllerEndpoint(brokerNum);
                var quorumVoters = IntStream.range(0, kraftControllers)
                        .mapToObj(b -> String.format("%d@%s", b, kafkaEndpoints.getControllerEndpoint(b).getConnect().toString())).collect(Collectors.joining(","));
                putConfig(server, "controller.quorum.voters", quorumVoters);
                putConfig(server, "controller.listener.names", "CONTROLLER");
                protocolMap.put("CONTROLLER", "PLAINTEXT");

                if (brokerNum == 0) {
                    putConfig(server, "process.roles", "broker,controller");

                    listeners.put("CONTROLLER", controllerEndpoint.getBind().toString());
                }
                else {
                    putConfig(server, "process.roles", "broker");
                }
            }
            else {
                putConfig(server, "zookeeper.connect", String.format("%s:%d", zookeeperEndpointSupplier.get().getHost(), zookeeperEndpointSupplier.get().getPort()));
                putConfig(server, "zookeeper.sasl.enabled", "false");
                putConfig(server, "zookeeper.connection.timeout.ms", Long.toString(60000));
            }

            putConfig(server, "listener.security.protocol.map",
                    protocolMap.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(",")));
            putConfig(server, "listeners", listeners.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(",")));
            putConfig(server, "advertised.listeners", advertisedListeners.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(",")));
            putConfig(server, "early.start.listeners", advertisedListeners.keySet().stream().map(Object::toString).collect(Collectors.joining(",")));

            if (saslMechanism != null) {
                putConfig(server, "sasl.enabled.mechanisms", saslMechanism);

                var saslPairs = new StringBuilder();

                Optional.of(users).orElse(Map.of()).forEach((key, value) -> {
                    saslPairs.append(String.format("user_%s", key));
                    saslPairs.append("=");
                    saslPairs.append(value);
                    saslPairs.append(" ");
                });

                // TODO support other than PLAIN
                String plainModuleConfig = String.format("org.apache.kafka.common.security.plain.PlainLoginModule required %s;", saslPairs);
                putConfig(server, String.format("listener.name.%s.plain.sasl.jaas.config", "EXTERNAL".toLowerCase()), plainModuleConfig);
            }

            putConfig(server, "offsets.topic.replication.factor", Integer.toString(1));
            // 1 partition for the __consumer_offsets_ topic should be enough
            putConfig(server, "offsets.topic.num.partitions", Integer.toString(1));
            // Disable delay during every re-balance
            putConfig(server, "group.initial.rebalance.delay.ms", Integer.toString(0));

            properties.add(new ConfigHolder(server, clientEndpoint.getConnect().getPort(),
                    String.format("%s:%d", clientEndpoint.getConnect().getHost(), clientEndpoint.getConnect().getPort()), brokerNum, kafkaKraftClusterId));
        }

        return properties.stream();
    }

    private static void putConfig(Properties server, String key, String value) {
        var orig = server.put(key, value);
        if (orig != null) {
            throw new RuntimeException("Cannot override broker config '" + key + "=" + value + "' with new value " + orig);
        }
    }

    protected Map<String, Object> getConnectConfigForCluster(String bootstrapServers) {
        if (saslMechanism != null) {
            Map<String, String> users = getUsers();
            if (!users.isEmpty()) {
                Map.Entry<String, String> first = users.entrySet().iterator().next();
                return getConnectConfigForCluster(bootstrapServers, first.getKey(), first.getKey());
            }
            else {
                return getConnectConfigForCluster(bootstrapServers, null, null);
            }
        }
        else {
            return getConnectConfigForCluster(bootstrapServers, null, null);
        }
    }

    protected Map<String, Object> getConnectConfigForCluster(String bootstrapServers, String user, String password) {
        Map<String, Object> kafkaConfig = new HashMap<>();
        String saslMechanism = getSaslMechanism();
        if (saslMechanism != null) {
            kafkaConfig.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT");
            kafkaConfig.put(SaslConfigs.SASL_MECHANISM, saslMechanism);

            if ("PLAIN".equals(saslMechanism)) {
                if (user != null && password != null) {
                    kafkaConfig.put(SaslConfigs.SASL_JAAS_CONFIG,
                            String.format("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";",
                                    user, password));
                }
            }
            else {
                throw new IllegalStateException(String.format("unsupported SASL mechanism %s", saslMechanism));
            }
        }

        kafkaConfig.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        return kafkaConfig;
    }

    public boolean isKraftMode() {
        return this.getKraftMode() == null || this.getKraftMode();
    }

    public String clusterId() {
        return isKraftMode() ? kafkaKraftClusterId : null;
    }

    @Builder
    @Getter
    public static class ConfigHolder {
        private final Properties properties;
        private final Integer externalPort;
        private final String endpoint;
        private final int brokerNum;
        private final String kafkaKraftClusterId;
    }

    protected interface KafkaEndpoints {

        @Builder
        @Getter
        class EndpointPair {
            private final Endpoint bind;
            private final Endpoint connect;
        }

        @Builder
        @Getter
        class Endpoint {
            private final String host;
            private final int port;

            @Override
            public String toString() {
                return String.format("//%s:%d", host, port);
            }
        }

        EndpointPair getInterBrokerEndpoint(int brokerId);

        EndpointPair getControllerEndpoint(int brokerId);

        EndpointPair getClientEndpoint(int brokerId);
    }
}
