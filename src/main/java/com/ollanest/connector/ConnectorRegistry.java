package com.ollanest.connector;

import com.ollanest.service.CryptoService;
import com.ollanest.service.RagService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spring-managed registry of all connector implementations.
 * Injects shared dependencies into each connector at construction time.
 */
@Component
public class ConnectorRegistry {

    private final Map<String, BaseConnector> byType;

    public ConnectorRegistry(List<BaseConnector> connectors,
                             JdbcTemplate db,
                             RagService ragService,
                             CryptoService cryptoService) {
        connectors.forEach(c -> c.setDependencies(db, ragService, cryptoService));
        byType = connectors.stream()
                .collect(Collectors.toMap(BaseConnector::getType, Function.identity()));
    }

    /** @return the connector for the given type key, or null if not found. */
    public BaseConnector get(String type) {
        return byType.get(type);
    }

    /** @return sorted list of all registered connector type keys. */
    public List<String> types() {
        return byType.keySet().stream().sorted().toList();
    }
}
