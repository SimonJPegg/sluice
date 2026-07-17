package org.antipathy.sluice.core.redis

import com.redis.testcontainers.RedisContainer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.utility.DockerImageName

abstract class RedisTest {
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
