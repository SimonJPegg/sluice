package org.antipathy.sluice.core.policy

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import kotlin.io.path.copyTo
import kotlin.io.path.deleteIfExists
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.antipathy.sluice.core.exceptions.InvalidPolicyConfigurationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir

class PolicyWatcherTest {

  private val apiGlobal =
      Policy(
          id = "api-global",
          limit = 100u,
          window = 1.minutes,
          algorithmType = AlgorithmType.FIXED_WINDOW,
          failType = FailType.OPEN,
      )

  private val apiHeavy =
      Policy(
          id = "api-heavy",
          limit = 10u,
          window = 1.minutes,
          algorithmType = AlgorithmType.SLIDING_WINDOW_COUNTER,
          failType = FailType.OPEN,
      )

  private val loginBruteForce =
      Policy(
          id = "login-brute-force",
          limit = 5u,
          window = 5.minutes,
          algorithmType = AlgorithmType.SLIDING_WINDOW_LOG,
          failType = FailType.CLOSED,
      )

  private val loginPerIp =
      Policy(
          id = "login-per-ip",
          limit = 20u,
          window = 10.minutes,
          algorithmType = AlgorithmType.TOKEN_BUCKET,
          failType = FailType.CLOSED,
      )

  private val webhookRetry =
      Policy(
          id = "webhook-retry",
          limit = 3u,
          window = 1.minutes,
          algorithmType = AlgorithmType.TOKEN_BUCKET,
          failType = FailType.CLOSED,
      )

  private fun resourcePath(resource: String): Path =
      Paths.get(
          (PolicyWatcherTest::class.java.getResource(resource)
                  ?: fail { "Unable to load resource $resource" })
              .path
      )

  @Suppress("LongParameterList") // I'm a test Jim, not a textbook example
  private fun runWatcherTest(
      policyDir: Path,
      setUp: (Path) -> Unit,
      preFlightChecks: (Map<String, Policy>) -> Unit,
      fileOperation: (PolicyWatcher, Path) -> Unit,
      postFlightChecks: (Map<String, Policy>) -> Unit,
      onReloadFailure: () -> Unit = {},
      expectChange: Boolean = true,
  ) = runBlocking {
    setUp(policyDir)
    val watcher = PolicyWatcher(policyDir, onReloadFailure = onReloadFailure)
    lateinit var watchJob: Job

    try {
      watcher.load()
      preFlightChecks(watcher.getPolicies())
      val policiesBeforeMutation = watcher.getPolicies()
      watchJob = launch { watcher.start() }
      fileOperation(watcher, policyDir)

      if (expectChange) {
        val deadline = System.currentTimeMillis() + 5000
        while (watcher.getPolicies() == policiesBeforeMutation) {
          if (System.currentTimeMillis() > deadline)
              fail { "Policies did not change within timeout" }
          delay(100.milliseconds)
        }
      } else {
        // give the watcher time to prove it doesn't react
        delay(2.seconds)
      }

      postFlightChecks(watcher.getPolicies())
    } finally {
      watcher.stop()
      watchJob.cancel()
    }
  }

  @Test
  fun `load populates policies from directory`() {
    val path = resourcePath("/policy/valid")
    val watcher = PolicyWatcher(path)
    watcher.load()
    val policies = watcher.getPolicies()

    assertEquals(apiGlobal, policies["api-global"])
    assertEquals(apiHeavy, policies["api-heavy"])
    assertEquals(loginBruteForce, policies["login-brute-force"])
    assertEquals(loginPerIp, policies["login-per-ip"])
    assertEquals(webhookRetry, policies["webhook-retry"])
  }

  @Test
  fun `load throws when directory is invalid`() {
    assertThrows<InvalidPolicyConfigurationException> {
      PolicyWatcher(Paths.get("/does/not/exist/")).load()
    }
  }

  @Test
  fun `start detects new file and reloads policies`(@TempDir tempDir: Path) =
      runWatcherTest(
          tempDir,
          setUp = { dir -> resourcePath("/policy/valid/api.yaml").copyTo(dir.resolve("init.yml")) },
          preFlightChecks = { policies ->
            assertEquals(2, policies.size)
            assertEquals(apiGlobal, policies["api-global"])
            assertEquals(apiHeavy, policies["api-heavy"])
          },
          fileOperation = { _, dir ->
            resourcePath("/policy/valid/webhooks.yaml").copyTo(dir.resolve("new.yml"))
          },
          postFlightChecks = { policies ->
            assertEquals(3, policies.size)
            assertEquals(webhookRetry, policies["webhook-retry"])
          },
      )

  @Test
  fun `start detects modified file and reloads policies`(@TempDir tempDir: Path) =
      runWatcherTest(
          tempDir,
          setUp = { dir -> resourcePath("/policy/valid/api.yaml").copyTo(dir.resolve("init.yml")) },
          preFlightChecks = { policies ->
            assertEquals(2, policies.size)
            assertEquals(apiGlobal, policies["api-global"])
            assertEquals(apiHeavy, policies["api-heavy"])
          },
          fileOperation = { _, dir ->
            resourcePath("/policy/valid/webhooks.yaml")
                .copyTo(dir.resolve("init.yml"), overwrite = true)
          },
          postFlightChecks = { policies ->
            assertEquals(1, policies.size)
            assertEquals(webhookRetry, policies["webhook-retry"])
            assertNull(policies["api-global"])
          },
      )

  @Test
  fun `start detects deleted file and reloads policies`(@TempDir tempDir: Path) =
      runWatcherTest(
          tempDir,
          setUp = { dir ->
            resourcePath("/policy/valid/api.yaml").copyTo(dir.resolve("first.yml"))
            resourcePath("/policy/valid/webhooks.yaml").copyTo(dir.resolve("second.yml"))
          },
          preFlightChecks = { policies ->
            assertEquals(3, policies.size)
            assertEquals(apiGlobal, policies["api-global"])
            assertEquals(apiHeavy, policies["api-heavy"])
            assertEquals(webhookRetry, policies["webhook-retry"])
          },
          fileOperation = { _, dir -> dir.resolve("first.yml").deleteIfExists() },
          postFlightChecks = { policies ->
            assertEquals(1, policies.size)
            assertEquals(webhookRetry, policies["webhook-retry"])
            assertNull(policies["api-global"])
          },
      )

  @Test
  fun `invalid YAML change does not overwrite existing policies`(@TempDir tempDir: Path) {
    var functionCalled = false
    runWatcherTest(
        tempDir,
        setUp = { dir -> resourcePath("/policy/valid/api.yaml").copyTo(dir.resolve("init.yml")) },
        preFlightChecks = { policies ->
          assertEquals(2, policies.size)
          assertEquals(apiGlobal, policies["api-global"])
          assertEquals(apiHeavy, policies["api-heavy"])
        },
        fileOperation = { _, dir ->
          dir.resolve("broken.yml").toFile().writeText("not: valid: yaml: [[[")
        },
        postFlightChecks = { policies ->
          assertEquals(2, policies.size)
          assertEquals(apiGlobal, policies["api-global"])
          assertEquals(apiHeavy, policies["api-heavy"])
        },
        onReloadFailure = { functionCalled = true },
        expectChange = false,
    )
    assertTrue(functionCalled)
  }

  @Test
  fun `stop closes watch service and exits loop`(@TempDir tempDir: Path) =
      runWatcherTest(
          tempDir,
          setUp = { dir -> resourcePath("/policy/valid/api.yaml").copyTo(dir.resolve("init.yml")) },
          preFlightChecks = { policies ->
            assertEquals(2, policies.size)
            assertEquals(apiGlobal, policies["api-global"])
            assertEquals(apiHeavy, policies["api-heavy"])
          },
          fileOperation = { watcher, dir ->
            watcher.stop()
            resourcePath("/policy/valid/webhooks.yaml").copyTo(dir.resolve("second.yml"))
          },
          postFlightChecks = { policies ->
            assertEquals(2, policies.size)
            assertEquals(apiGlobal, policies["api-global"])
            assertEquals(apiHeavy, policies["api-heavy"])
          },
          expectChange = false,
      )

  @Test
  fun `policy watcher can handle k8s configmap swaps`(@TempDir tempDir: Path) = runBlocking {
    val rev1 = tempDir.resolve("rev1")
    val rev2 = tempDir.resolve("rev2")
    Files.createDirectory(rev1)
    Files.createDirectory(rev2)

    resourcePath("/policy/valid/api.yaml").copyTo(rev1.resolve("policies.yml"))
    resourcePath("/policy/valid/webhooks.yaml").copyTo(rev2.resolve("policies.yml"))
    Files.setLastModifiedTime(
        rev2.resolve("policies.yml"),
        FileTime.fromMillis(System.currentTimeMillis() + 10_000),
    )

    val link = tempDir.resolve("active")
    Files.createSymbolicLink(link, rev1)

    val watcher = PolicyWatcher(link, pollInterval = 1.seconds)
    lateinit var watchJob: Job

    try {
      watcher.load()
      assertEquals(2, watcher.getPolicies().size)
      assertEquals(apiGlobal, watcher.getPolicies()["api-global"])
      assertEquals(apiHeavy, watcher.getPolicies()["api-heavy"])

      watchJob = launch { watcher.start() }

      Files.delete(link)
      Files.createSymbolicLink(link, rev2)

      val deadline = System.currentTimeMillis() + 5000
      while (watcher.getPolicies()["webhook-retry"] == null) {
        if (System.currentTimeMillis() > deadline) fail { "Policy not reloaded within timeout" }
        delay(100.milliseconds)
      }

      assertEquals(1, watcher.getPolicies().size)
      assertEquals(webhookRetry, watcher.getPolicies()["webhook-retry"])
      assertNull(watcher.getPolicies()["api-global"])
    } finally {
      watcher.stop()
      watchJob.cancel()
    }
  }
}
