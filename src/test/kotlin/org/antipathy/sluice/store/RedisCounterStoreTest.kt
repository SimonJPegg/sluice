package org.antipathy.sluice.store

import com.redis.testcontainers.RedisContainer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlin.test.Test
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.utility.DockerImageName

abstract class RedisCounterStoreTest {

  lateinit var client: RedisClient
  lateinit var connection: StatefulRedisConnection<String, String>

  @BeforeEach
  fun before() {
    client = RedisClient.create(redisServer.redisURI)
    connection = client.connect()
    connection.sync().flushall()
  }

  @AfterEach
  fun after() {
    connection.close()
    client.close()
  }


  @Test
  fun `validate testcontainer is working as expected`() {
    val commands = connection.sync()
    assertEquals("PONG",commands.ping())
  }

  companion object {
    val redisServer = RedisContainer(DockerImageName.parse("redis:8.8.0"))
    @JvmStatic
    @BeforeAll
    fun setUp() {
      redisServer.start()
    }

    @JvmStatic
    @AfterAll
    fun tearDown() {
      redisServer.stop()
    }
  }

}

