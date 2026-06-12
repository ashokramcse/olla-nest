package com.ollanest.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for all 20 connector implementations in
 * {@code com.ollanest.connector.impl}.
 *
 * <p>
 * Each connector is instantiated with its no-arg constructor (dependencies are
 * injected post-construction by {@code ConnectorRegistry} via
 * {@code BaseConnector.setDependencies()}). Tests verify construction succeeds
 * and {@code getType()} returns the expected stable type key.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The connector registry maps each connector to a stable string type key that
 * is persisted in {@code connector_configs} and used throughout the sync and
 * routing layers. A construction failure or a changed type key would silently
 * break connector wiring at runtime; these tests pin both invariants so any
 * regression is caught at build time rather than in production.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Each connector lives in its own {@link Nested} block so a failure is
 * attributed to a single connector in the test report.</li>
 * <li>Lenient Mockito strictness is applied class-wide because these
 * construction-only tests stub nothing — strict stubbing would be noise.</li>
 * <li>The type-key assertions act as a change-detector contract: the literal
 * keys must never change without a coordinated schema migration.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — documented as part of the project-wide Javadoc pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConnectorImpl — unit tests")
class ConnectorImplTest {

	/**
	 * Construction and type-key contract tests for {@link GitHubConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("GitHubConnector")
	class GitHubConnectorTests {
		/**
		 * Verifies that {@link GitHubConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new GitHubConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link GitHubConnector#getType()} returns the stable key
		 * {@code "github"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'github'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new GitHubConnector().getType()).isEqualTo("github");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link GitLabConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("GitLabConnector")
	class GitLabConnectorTests {
		/**
		 * Verifies that {@link GitLabConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new GitLabConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link GitLabConnector#getType()} returns the stable key
		 * {@code "gitlab"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'gitlab'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new GitLabConnector().getType()).isEqualTo("gitlab");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link SlackConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("SlackConnector")
	class SlackConnectorTests {
		/**
		 * Verifies that {@link SlackConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new SlackConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link SlackConnector#getType()} returns the stable key
		 * {@code "slack"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'slack'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new SlackConnector().getType()).isEqualTo("slack");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link NotionConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("NotionConnector")
	class NotionConnectorTests {
		/**
		 * Verifies that {@link NotionConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new NotionConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link NotionConnector#getType()} returns the stable key
		 * {@code "notion"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'notion'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new NotionConnector().getType()).isEqualTo("notion");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link GoogleDriveConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("GoogleDriveConnector")
	class GoogleDriveConnectorTests {
		/**
		 * Verifies that {@link GoogleDriveConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new GoogleDriveConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link GoogleDriveConnector#getType()} returns the stable key
		 * {@code "gdrive"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'gdrive'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new GoogleDriveConnector().getType()).isEqualTo("gdrive");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link GmailConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("GmailConnector")
	class GmailConnectorTests {
		/**
		 * Verifies that {@link GmailConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new GmailConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link GmailConnector#getType()} returns the stable key
		 * {@code "gmail"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'gmail'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new GmailConnector().getType()).isEqualTo("gmail");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link JiraConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("JiraConnector")
	class JiraConnectorTests {
		/**
		 * Verifies that {@link JiraConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new JiraConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link JiraConnector#getType()} returns the stable key
		 * {@code "jira"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'jira'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new JiraConnector().getType()).isEqualTo("jira");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link ConfluenceConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("ConfluenceConnector")
	class ConfluenceConnectorTests {
		/**
		 * Verifies that {@link ConfluenceConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new ConfluenceConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link ConfluenceConnector#getType()} returns the stable key
		 * {@code "confluence"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'confluence'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new ConfluenceConnector().getType()).isEqualTo("confluence");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link AsanaConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("AsanaConnector")
	class AsanaConnectorTests {
		/**
		 * Verifies that {@link AsanaConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new AsanaConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link AsanaConnector#getType()} returns the stable key
		 * {@code "asana"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'asana'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new AsanaConnector().getType()).isEqualTo("asana");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link BitbucketConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("BitbucketConnector")
	class BitbucketConnectorTests {
		/**
		 * Verifies that {@link BitbucketConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new BitbucketConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link BitbucketConnector#getType()} returns the stable key
		 * {@code "bitbucket"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'bitbucket'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new BitbucketConnector().getType()).isEqualTo("bitbucket");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link LinearConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("LinearConnector")
	class LinearConnectorTests {
		/**
		 * Verifies that {@link LinearConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new LinearConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link LinearConnector#getType()} returns the stable key
		 * {@code "linear"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'linear'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new LinearConnector().getType()).isEqualTo("linear");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link DiscordConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("DiscordConnector")
	class DiscordConnectorTests {
		/**
		 * Verifies that {@link DiscordConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new DiscordConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link DiscordConnector#getType()} returns the stable key
		 * {@code "discord"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'discord'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new DiscordConnector().getType()).isEqualTo("discord");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link TeamsConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("TeamsConnector")
	class TeamsConnectorTests {
		/**
		 * Verifies that {@link TeamsConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new TeamsConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link TeamsConnector#getType()} returns the stable key
		 * {@code "teams"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'teams'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new TeamsConnector().getType()).isEqualTo("teams");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link HubSpotConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("HubSpotConnector")
	class HubSpotConnectorTests {
		/**
		 * Verifies that {@link HubSpotConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new HubSpotConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link HubSpotConnector#getType()} returns the stable key
		 * {@code "hubspot"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'hubspot'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new HubSpotConnector().getType()).isEqualTo("hubspot");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link SalesforceConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("SalesforceConnector")
	class SalesforceConnectorTests {
		/**
		 * Verifies that {@link SalesforceConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new SalesforceConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link SalesforceConnector#getType()} returns the stable key
		 * {@code "salesforce"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'salesforce'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new SalesforceConnector().getType()).isEqualTo("salesforce");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link FigmaConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("FigmaConnector")
	class FigmaConnectorTests {
		/**
		 * Verifies that {@link FigmaConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new FigmaConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link FigmaConnector#getType()} returns the stable key
		 * {@code "figma"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'figma'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new FigmaConnector().getType()).isEqualTo("figma");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link ZendeskConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("ZendeskConnector")
	class ZendeskConnectorTests {
		/**
		 * Verifies that {@link ZendeskConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new ZendeskConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link ZendeskConnector#getType()} returns the stable key
		 * {@code "zendesk"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'zendesk'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new ZendeskConnector().getType()).isEqualTo("zendesk");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link DropboxConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("DropboxConnector")
	class DropboxConnectorTests {
		/**
		 * Verifies that {@link DropboxConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new DropboxConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link DropboxConnector#getType()} returns the stable key
		 * {@code "dropbox"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'dropbox'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new DropboxConnector().getType()).isEqualTo("dropbox");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link OneDriveConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("OneDriveConnector")
	class OneDriveConnectorTests {
		/**
		 * Verifies that {@link OneDriveConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new OneDriveConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link OneDriveConnector#getType()} returns the stable key
		 * {@code "onedrive"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'onedrive'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new OneDriveConnector().getType()).isEqualTo("onedrive");
		}
	}

	/**
	 * Construction and type-key contract tests for {@link AirtableConnector}.
	 *
	 * <p>
	 * Grouped in a dedicated nested class so a failure points unambiguously at
	 * this one connector in the test report.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("AirtableConnector")
	class AirtableConnectorTests {
		/**
		 * Verifies that {@link AirtableConnector} can be instantiated via its no-arg
		 * constructor without throwing. A successful construction proves the
		 * connector has no mandatory constructor dependencies, which is required
		 * because the registry injects collaborators after construction.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("construction succeeds")
		void constructionSucceeds() {
			// No exception = no required constructor args missing
			assertThat(new AirtableConnector()).isNotNull();
		}

		/**
		 * Verifies that {@link AirtableConnector#getType()} returns the stable key
		 * {@code "airtable"}. This key is used as the registry map key and is
		 * persisted in {@code connector_configs}, so it must never change.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getType() returns 'airtable'")
		void getType() {
			// Stable type key used as map key in ConnectorRegistry — must never change
			assertThat(new AirtableConnector().getType()).isEqualTo("airtable");
		}
	}
}
