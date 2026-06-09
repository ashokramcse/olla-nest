package com.ollanest.connector;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ollanest.service.CryptoService;
import com.ollanest.service.RagService;

/**
 * Spring-managed registry that maps connector type keys to their
 * implementations.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest ships with 20 connector implementations, each extending
 * {@link BaseConnector}. The {@link ConnectorSyncScheduler} and the
 * {@code AdminConnectorController} need to look up the correct implementation
 * at runtime given only the string type key stored in
 * {@code connector_configs.type} (e.g. {@code "github"}, {@code "slack"}).
 * {@code ConnectorRegistry} is the single source of truth for this dispatch: it
 * collects every {@link BaseConnector} bean Spring discovers, injects the
 * shared infrastructure dependencies into each, and exposes a simple
 * {@link #get(String)} lookup.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Spring auto-discovers all {@link BaseConnector} subclasses annotated with
 * {@code @Component} and injects them as a {@link List} into the
 * constructor.</li>
 * <li>Dependencies ({@link JdbcTemplate}, {@link RagService},
 * {@link CryptoService}) are injected once here and forwarded to each connector
 * via {@link BaseConnector#setDependencies} so that connector classes remain
 * plain Java objects with no Spring annotations.</li>
 * <li>The internal map is keyed by {@link BaseConnector#getType()} — duplicate
 * type keys would silently overwrite each other, so all 20 connectors must
 * return distinct type strings.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.4</b> — initial creation; auto-discovery of all
 * {@link BaseConnector} beans; dependency injection forwarding; {@link #get}
 * and {@link #types} API.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.4
 * @version v2026.1.4
 * @see BaseConnector
 * @see ConnectorSyncScheduler
 */
@Component
public class ConnectorRegistry {

	/**
	 * Internal map from connector type key (e.g. {@code "github"}) to the
	 * corresponding {@link BaseConnector} instance with all dependencies already
	 * injected.
	 */
	private final Map<String, BaseConnector> byType;

	/**
	 * Constructs the registry by collecting all discovered connector
	 * implementations, injecting shared dependencies into each, and building the
	 * type-keyed dispatch map.
	 *
	 * @param connectors    all {@link BaseConnector} beans discovered by Spring
	 * @param db            JDBC template forwarded to each connector
	 * @param ragService    RAG service forwarded to each connector for document
	 *                      ingestion
	 * @param cryptoService crypto service forwarded to each connector for
	 *                      credential decryption
	 * @since v2026.1.4
	 */
	public ConnectorRegistry(List<BaseConnector> connectors, JdbcTemplate db, RagService ragService,
			CryptoService cryptoService) {
		connectors.forEach(c -> c.setDependencies(db, ragService, cryptoService));
		byType = connectors.stream().collect(Collectors.toMap(BaseConnector::getType, Function.identity()));
	}

	/**
	 * Looks up the connector implementation for a given type key.
	 *
	 * @param type the connector type key (e.g. {@code "github"}, {@code "slack"})
	 * @return the {@link BaseConnector} for that type, or {@code null} if no
	 *         connector with that type key has been registered
	 * @since v2026.1.4
	 */
	public BaseConnector get(String type) {
		return byType.get(type);
	}

	/**
	 * Returns an alphabetically sorted list of all registered connector type keys.
	 *
	 * <p>
	 * Used by the admin UI to present the list of available connector types when
	 * creating a new connector configuration.
	 *
	 * @return immutable sorted list of all registered type keys; never null
	 * @since v2026.1.4
	 */
	public List<String> types() {
		return byType.keySet().stream().sorted().toList();
	}
}
