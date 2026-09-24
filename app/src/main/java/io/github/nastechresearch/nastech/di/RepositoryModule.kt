package io.github.nastechresearch.nastech.di

import android.content.Context
import io.github.nastechresearch.nastech.data.files.FileFolders
import io.github.nastechresearch.nastech.data.files.FilesManager
import io.github.nastechresearch.nastech.data.files.SkillManager
import io.github.nastechresearch.nastech.data.memory.ConversationMemoryEngine
import io.github.nastechresearch.nastech.data.agent.AgentConfigRepository
import io.github.nastechresearch.nastech.data.agent.ConversationAgentRuntime
import io.github.nastechresearch.nastech.data.task.TaskManager
import io.github.nastechresearch.nastech.data.execution.ExecutionTelemetry
import io.github.nastechresearch.nastech.data.execution.LocalExecutionEngine
import io.github.nastechresearch.nastech.data.execution.DeviceAgentCore
import io.github.nastechresearch.nastech.data.execution.DeviceStateCache
import io.github.nastechresearch.nastech.data.execution.DeviceObserver
import io.github.nastechresearch.nastech.data.execution.AndroidDeviceObserver
import io.github.nastechresearch.nastech.data.execution.DeviceTargetResolver
import io.github.nastechresearch.nastech.data.execution.AndroidAccessibilityTargetLookup
import io.github.nastechresearch.nastech.data.execution.VisionPipelineTargetLookup
import io.github.nastechresearch.nastech.data.execution.DevicePostconditionVerifier
import io.github.nastechresearch.nastech.data.execution.AndroidDeviceStateVerifier
import io.github.nastechresearch.nastech.data.execution.DeviceStateWaiter
import io.github.nastechresearch.nastech.data.execution.BoundedDeviceStateWaiter
import io.github.nastechresearch.nastech.data.execution.DeviceRecoveryEngine
import io.github.nastechresearch.nastech.data.vision.VisionPipeline
import io.github.nastechresearch.nastech.data.repository.ConversationRepository
import io.github.nastechresearch.nastech.data.repository.FavoriteRepository
import io.github.nastechresearch.nastech.data.repository.FolderRepository
import io.github.nastechresearch.nastech.data.repository.FilesRepository
import io.github.nastechresearch.nastech.data.repository.GenMediaRepository
import io.github.nastechresearch.nastech.data.repository.MemoryRepository
import io.github.nastechresearch.nastech.data.repository.WorkspaceRepository
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        ConversationMemoryEngine(get())
    }

    single {
        TaskManager(get())
    }

    single { ExecutionTelemetry(get()) }

    single { DeviceStateCache() }

    single<DeviceObserver> {
        AndroidDeviceObserver(
            context = get(),
            cache = get(),
        )
    }

    single {
        DeviceTargetResolver(
            cache = get(),
            accessibilityLookup = AndroidAccessibilityTargetLookup(),
            visionLookup = VisionPipelineTargetLookup(
                VisionPipeline(
                    context = get(),
                    providerManager = get(),
                )
            ),
        )
    }

    single<DevicePostconditionVerifier> {
        AndroidDeviceStateVerifier(
            context = get(),
            observer = get(),
        )
    }

    single<DeviceStateWaiter> {
        BoundedDeviceStateWaiter(verifier = get())
    }

    single { DeviceRecoveryEngine(maxAttempts = 4) }

    single {
        DeviceAgentCore(
            context = get(),
            telemetry = get(),
            taskManager = get(),
            stateCache = get(),
            observer = get(),
            targetResolver = get(),
            verifier = get(),
            waiter = get(),
            recoveryEngine = get(),
            agentConfigRepository = get(),
        )
    }

    single {
        LocalExecutionEngine(
            taskManager = get(),
            telemetry = get(),
            deviceAgentCore = get(),
        )
    }

    single { AgentConfigRepository(get()) }

    single { ConversationAgentRuntime(get(), get(), get(), get()) }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            ),
            // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }
}
