package com.agentvillage.connector.slack.infrastructure

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

data class SlackTeam(@JsonProperty("id") val id: String = "", @JsonProperty("name") val name: String = "")
data class SlackAuthedUser(@JsonProperty("id") val id: String = "")
data class SlackOauthResult(
    val ok: Boolean = false,
    val error: String? = null,
    @JsonProperty("access_token") val accessToken: String = "",
    val scope: String = "",
    val team: SlackTeam = SlackTeam(),
    @JsonProperty("authed_user") val authedUser: SlackAuthedUser = SlackAuthedUser(),
    @JsonProperty("bot_user_id") val botUserId: String? = null,
    @JsonProperty("app_id") val appId: String? = null,
)

interface SlackTokenExchange { fun exchange(code: String, redirectUri: String): SlackOauthResult }

@Component
class HttpSlackTokenExchange(
    builder: RestClient.Builder,
    @Value("\${connectors.slack.api-base-url:https://slack.com}") baseUrl: String,
    @Value("\${connectors.slack.client-id:}") private val clientId: String,
    @Value("\${connectors.slack.client-secret:}") private val clientSecret: String,
) : SlackTokenExchange {
    private val client = builder.baseUrl(baseUrl.removeSuffix("/")).build()
    override fun exchange(code: String, redirectUri: String): SlackOauthResult {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", clientId); add("client_secret", clientSecret); add("code", code); add("redirect_uri", redirectUri)
        }
        return client.post().uri("/api/oauth.v2.access").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form).retrieve().body(SlackOauthResult::class.java) ?: SlackOauthResult(error = "empty_response")
    }
}
