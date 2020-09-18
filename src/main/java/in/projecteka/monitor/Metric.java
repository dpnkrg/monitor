package in.projecteka.monitor;

import in.projecteka.monitor.model.HeartbeatResponse;
import in.projecteka.monitor.model.Service;
import in.projecteka.monitor.model.ServiceProperties;
import in.projecteka.monitor.model.Status;
import io.prometheus.client.Gauge;
import lombok.AllArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;

import static in.projecteka.monitor.model.Status.DOWN;
import static java.time.ZoneOffset.UTC;
import static reactor.core.publisher.Mono.defer;
import static reactor.core.publisher.Mono.just;

@AllArgsConstructor
public class Metric {
    public static final String PROJECT_EKA_METRICS = "Projecteka_metrics_";
    private final MetricRepository metricRepository;
    private final MetricServiceClient metricServiceClient;
    private static final HashMap<String, Gauge> gaugeMap = new HashMap<>();

    public void processRequests(Boolean isReadiness) {
        Service service = metricServiceClient.getService();
        process(service.getBridgeProperties(), isReadiness);
        process(service.getConsentManagerProperties(), isReadiness);
    }

    private void process(List<ServiceProperties> properties, Boolean isReadiness) {
        properties.forEach(property -> {
            String path = isReadiness ? String.format("%s%s", property.getUrl(), Constants.PATH_READINESS)
                    : String.format("%s%s", property.getUrl(), Constants.PATH_HEARTBEAT);
            HeartbeatResponse heartbeatResponse = metricServiceClient.getHeartbeat(path);
            if (!gaugeMap.containsKey(PROJECT_EKA_METRICS + property.getType())) {
                Gauge gaugeStatus = Gauge.build()
                        .labelNames("Name", "Id", "Path", "Status", "LastUpTime")
                        .name(PROJECT_EKA_METRICS + property.getType())
                        .help("Heartbeat Status")
                        .register();
                gaugeMap.put(PROJECT_EKA_METRICS + property.getType(), gaugeStatus);
                appendToStatus(property, path, heartbeatResponse, gaugeStatus, isReadiness);
            } else {
                appendToStatus(property, path, heartbeatResponse, gaugeMap.get(PROJECT_EKA_METRICS + property.getType()), isReadiness);
            }
        });
    }

    private void appendToStatus(ServiceProperties property, String path,
                                HeartbeatResponse heartbeatResponse,
                                Gauge status,
                                Boolean isReadiness) {
        if (heartbeatResponse.getStatus().equals(DOWN)) {
            String lastUpTime = metricRepository.getIfPresent(path, isReadiness).block();
            status.labels(property.getName(),
                    property.getId(),
                    path,
                    heartbeatResponse.getStatus().toString(),
                    lastUpTime).set(0);
        } else {
            LocalDateTime lastUpTime = LocalDateTime.now(UTC);
            Boolean isPresent = isEntryPresent(path, isReadiness).block();
            if (isPresent) {
                metricRepository.update(lastUpTime, path, isReadiness).block();
            } else {
                metricRepository.insert(path, Status.UP.toString(), lastUpTime, isReadiness).block();
            }
            status.labels(property.getName(),
                    property.getId(),
                    path,
                    heartbeatResponse.getStatus().toString(),
                    lastUpTime.toString()).inc();
        }
    }

    private Mono<Boolean> isEntryPresent(String path, Boolean isReadiness) {
        return metricRepository.getIfPresent(path, isReadiness)
                .map(entry -> !entry.equals(""))
                .switchIfEmpty(defer(() -> just(false)));
    }
}
