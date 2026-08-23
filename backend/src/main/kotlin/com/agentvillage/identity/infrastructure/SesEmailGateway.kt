package com.agentvillage.identity.infrastructure

import com.agentvillage.identity.application.EmailGateway
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.Body
import software.amazon.awssdk.services.sesv2.model.Content
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.Message
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest

@Component
@ConditionalOnProperty(name = ["auth.email.provider"], havingValue = "ses")
class SesEmailGateway(
    @Value("\${auth.email.ses-region:ap-northeast-2}") region: String,
    @Value("\${auth.email.ses-access-key}") accessKey: String,
    @Value("\${auth.email.ses-secret-key}") secretKey: String,
    @Value("\${auth.email.from-address}") private val fromAddress: String,
) : EmailGateway {
    private val client = SesV2Client.builder()
        .region(Region.of(region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .build()

    override fun send(to: String, subject: String, body: String) {
        val message = Message.builder()
            .subject(Content.builder().data(subject).charset("UTF-8").build())
            .body(Body.builder().text(Content.builder().data(body).charset("UTF-8").build()).build())
            .build()
        client.sendEmail(SendEmailRequest.builder().fromEmailAddress(fromAddress).destination { it.toAddresses(to) }.content(EmailContent.builder().simple(message).build()).build())
    }
}
