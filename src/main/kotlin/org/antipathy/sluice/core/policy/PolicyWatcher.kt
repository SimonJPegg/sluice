package org.antipathy.sluice.core.policy

import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** Watches a policy directory for changes via inotify and polls for symlink swaps. */
class PolicyWatcher(
    private val policyPath: Path,
    private val pollInterval: Duration = 30.seconds,
    private val onReloadFailure: () -> Unit = {},
) {

  @Volatile private var active = true

  private val logger = LoggerFactory.getLogger(PolicyWatcher::class.java)
  private val watchService = FileSystems.getDefault().newWatchService()
  private val policies: AtomicReference<Map<String, Policy>> = AtomicReference(emptyMap())
  private val lastModified: AtomicReference<FileTime> = AtomicReference(FileTime.fromMillis(0))

  fun getPolicies(): Map<String, Policy> = policies.get()

  fun load() {
    reload()
    policyPath.register(
        watchService,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.ENTRY_DELETE,
    )
  }

  // this is deliberate, we want to keep the existing policies and not fall over
  @Suppress("TooGenericExceptionCaught")
  suspend fun start() {
    while (active) {
      try {
        val key =
            withContext(Dispatchers.IO) {
              // k8s ConfigMap volumes don't modify files directly. They create a new directory
              // and swap a symlink to point at it. We're watching the target, so our filewatcher
              // never fires. The poll fallback compares mtimes through the symlink
              // (we still get the 'take')
              watchService.poll(pollInterval.toJavaDuration().toMillis(), TimeUnit.MILLISECONDS)
            }
        if (key != null) {
          delay(500.milliseconds)
          key.reset()
          reload()
        } else {
          checkForSymlinkChange()
        }
      } catch (_: ClosedWatchServiceException) {
        logger.warn("file monitoring ceased, no future policy updates will be read")
        active = false
      } catch (e: Exception) {
        logger.error("Error while updating policies", e)
        onReloadFailure()
      }
    }
  }

  fun stop() {
    active = false
    try {
      watchService.close()
    } catch (_: ClosedWatchServiceException) {
      logger.debug("watch service already closed")
    }
  }

  private fun reload() {
    policies.set(PolicyReader.read(policyPath))
    lastModified.set(resolveLatestModified())
  }

  private fun checkForSymlinkChange() {
    val current = resolveLatestModified()
    if (current != lastModified.get()) {
      logger.info("Detected policy change via poll (symlink swap or missed event)")
      reload()
    }
  }

  /** Returns the newest mtime across all YAML files in the resolved directory. */
  private fun resolveLatestModified(): FileTime =
      policyPath.toRealPath().listDirectoryEntries("*.{yaml,yml}").maxOfOrNull {
        Files.getLastModifiedTime(it)
      } ?: FileTime.fromMillis(0)
}
