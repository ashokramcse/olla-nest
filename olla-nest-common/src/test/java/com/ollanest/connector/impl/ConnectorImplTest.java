package com.ollanest.connector.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for all 20 connector implementations in {@code com.ollanest.connector.impl}.
 *
 * <p>Each connector is instantiated with its no-arg constructor (dependencies are
 * injected post-construction by {@code ConnectorRegistry} via
 * {@code BaseConnector.setDependencies()}). Tests verify construction succeeds and
 * {@code getType()} returns the expected stable type key.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConnectorImpl — unit tests")
class ConnectorImplTest {

    @Nested
    @DisplayName("GitHubConnector")
    class GitHubConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            // No exception = no required constructor args missing
            assertThat(new GitHubConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'github'")
        void getType() {
            // Stable type key used as map key in ConnectorRegistry — must never change
            assertThat(new GitHubConnector().getType()).isEqualTo("github");
        }
    }

    @Nested
    @DisplayName("GitLabConnector")
    class GitLabConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new GitLabConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'gitlab'")
        void getType() {
            assertThat(new GitLabConnector().getType()).isEqualTo("gitlab");
        }
    }

    @Nested
    @DisplayName("SlackConnector")
    class SlackConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new SlackConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'slack'")
        void getType() {
            assertThat(new SlackConnector().getType()).isEqualTo("slack");
        }
    }

    @Nested
    @DisplayName("NotionConnector")
    class NotionConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new NotionConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'notion'")
        void getType() {
            assertThat(new NotionConnector().getType()).isEqualTo("notion");
        }
    }

    @Nested
    @DisplayName("GoogleDriveConnector")
    class GoogleDriveConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new GoogleDriveConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'gdrive'")
        void getType() {
            assertThat(new GoogleDriveConnector().getType()).isEqualTo("gdrive");
        }
    }

    @Nested
    @DisplayName("GmailConnector")
    class GmailConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new GmailConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'gmail'")
        void getType() {
            assertThat(new GmailConnector().getType()).isEqualTo("gmail");
        }
    }

    @Nested
    @DisplayName("JiraConnector")
    class JiraConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new JiraConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'jira'")
        void getType() {
            assertThat(new JiraConnector().getType()).isEqualTo("jira");
        }
    }

    @Nested
    @DisplayName("ConfluenceConnector")
    class ConfluenceConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new ConfluenceConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'confluence'")
        void getType() {
            assertThat(new ConfluenceConnector().getType()).isEqualTo("confluence");
        }
    }

    @Nested
    @DisplayName("AsanaConnector")
    class AsanaConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new AsanaConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'asana'")
        void getType() {
            assertThat(new AsanaConnector().getType()).isEqualTo("asana");
        }
    }

    @Nested
    @DisplayName("BitbucketConnector")
    class BitbucketConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new BitbucketConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'bitbucket'")
        void getType() {
            assertThat(new BitbucketConnector().getType()).isEqualTo("bitbucket");
        }
    }

    @Nested
    @DisplayName("LinearConnector")
    class LinearConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new LinearConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'linear'")
        void getType() {
            assertThat(new LinearConnector().getType()).isEqualTo("linear");
        }
    }

    @Nested
    @DisplayName("DiscordConnector")
    class DiscordConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new DiscordConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'discord'")
        void getType() {
            assertThat(new DiscordConnector().getType()).isEqualTo("discord");
        }
    }

    @Nested
    @DisplayName("TeamsConnector")
    class TeamsConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new TeamsConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'teams'")
        void getType() {
            assertThat(new TeamsConnector().getType()).isEqualTo("teams");
        }
    }

    @Nested
    @DisplayName("HubSpotConnector")
    class HubSpotConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new HubSpotConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'hubspot'")
        void getType() {
            assertThat(new HubSpotConnector().getType()).isEqualTo("hubspot");
        }
    }

    @Nested
    @DisplayName("SalesforceConnector")
    class SalesforceConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new SalesforceConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'salesforce'")
        void getType() {
            assertThat(new SalesforceConnector().getType()).isEqualTo("salesforce");
        }
    }

    @Nested
    @DisplayName("FigmaConnector")
    class FigmaConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new FigmaConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'figma'")
        void getType() {
            assertThat(new FigmaConnector().getType()).isEqualTo("figma");
        }
    }

    @Nested
    @DisplayName("ZendeskConnector")
    class ZendeskConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new ZendeskConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'zendesk'")
        void getType() {
            assertThat(new ZendeskConnector().getType()).isEqualTo("zendesk");
        }
    }

    @Nested
    @DisplayName("DropboxConnector")
    class DropboxConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new DropboxConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'dropbox'")
        void getType() {
            assertThat(new DropboxConnector().getType()).isEqualTo("dropbox");
        }
    }

    @Nested
    @DisplayName("OneDriveConnector")
    class OneDriveConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new OneDriveConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'onedrive'")
        void getType() {
            assertThat(new OneDriveConnector().getType()).isEqualTo("onedrive");
        }
    }

    @Nested
    @DisplayName("AirtableConnector")
    class AirtableConnectorTests {
        @Test @DisplayName("construction succeeds")
        void constructionSucceeds() {
            assertThat(new AirtableConnector()).isNotNull();
        }

        @Test @DisplayName("getType() returns 'airtable'")
        void getType() {
            assertThat(new AirtableConnector().getType()).isEqualTo("airtable");
        }
    }
}
