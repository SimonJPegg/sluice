package org.antipathy.sluice.redis

import com.redis.testcontainers.RedisContainer
import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Duration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.Network
import org.testcontainers.toxiproxy.ToxiproxyContainer
import org.testcontainers.utility.DockerImageName

abstract class RedisTest {
  lateinit var redisClient: RedisClient
  lateinit var redisConnection: StatefulRedisConnection<String, String>

  @BeforeEach
  fun before() {
    redisClient = RedisClient.create("redis://${toxiproxy.host}:${toxiproxy.getMappedPort(8666)}")
    // testing timeouts is about as much fun as it looks
    redisClient.options =
        ClientOptions.builder()
            .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(50)))
            .build()
    redisConnection = redisClient.connect()
    redisConnection.sync().flushall()
  }

  @AfterEach
  fun after() {
    redisConnection.close()
    redisClient.close()
    proxy.toxics().all.forEach { it.remove() }
  }

  companion object {
    val network = Network.newNetwork()
    val redisServer =
        RedisContainer(DockerImageName.parse("redis:8.8.0"))
            .withNetwork(network)
            .withNetworkAliases("redis")
    lateinit var proxy: Proxy
    val toxiproxy: ToxiproxyContainer =
        ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.9.0"))
            .withNetwork(network)

    @JvmStatic
    @BeforeAll
    fun setUp() {
      redisServer.start()
      toxiproxy.start()
      val toxiproxyClient = ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort)
      proxy = toxiproxyClient.createProxy("redis", "0.0.0.0:8666", "redis:6379")
    }

    @JvmStatic
    @AfterAll
    fun tearDown() {
      redisServer.stop()
      toxiproxy.stop()
      network.close()
    }
  }
}
