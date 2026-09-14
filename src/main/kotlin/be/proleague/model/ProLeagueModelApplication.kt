package be.proleague.model

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@ConfigurationPropertiesScan
class ProLeagueModelApplication {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    // SofaScore's edge fingerprints the TLS ClientHello and answers 403 "challenge" to anything
    // that is not a browser. The one extension that gives the JVM away is the legacy RFC 5077
    // session_ticket, which JSSE offers by default and browsers dropped; without it the same
    // requests are served normally. JSSE reads this once, so it has to be set before the first
    // handshake -- hence here rather than in SofaScoreConfiguration.
    System.setProperty("jdk.tls.client.enableSessionTicketExtension", "false")
    runApplication<ProLeagueModelApplication>(*args)
}
