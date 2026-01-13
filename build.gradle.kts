import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.js) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

allprojects {
    group = "com.example"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
            }
        }
    }

    detekt {
        buildUponDefaultConfig = true
        allRules = false
        autoCorrect = false
        config.setFrom(files("$rootDir/detekt.yml"))
    }
}

ktlint {
    version.set(libs.versions.ktlint.get())
    android.set(false)
    verbose.set(true)
}

// --- Limit Kotlin/JS yarn.lock enforcement to packaging/publish builds for selected projects (config-cache friendly) ---
val skipJsLockCheck: Boolean =
    (providers.gradleProperty("skipJsLockCheck").orNull?.toBoolean()) ?: false
val forceJsLockCheck: Boolean =
    (providers.gradleProperty("forceJsLockCheck").orNull?.toBoolean()) ?: false
val jsLockVerbose: Boolean =
    (providers.gradleProperty("jsLockVerbose").orNull?.toBoolean()) ?: true
val barePackagingOrPublishNames = setOf(
    "build",
    "assemble",
    "assembleDist",
    "jar",
    "shadowJar",
    "fatJar",
    "installDist",
    "installShadowDist",
    "distZip",
    "distTar",
    "shadowDistZip",
    "shadowDistTar",
    "publish",
    "publishToMavenLocal",
    "publishAllPublicationsToMavenRepository"
)

fun org.gradle.api.logging.Logger.note(msg: String) {
    if (jsLockVerbose) lifecycle(msg) else info(msg)
}

// По умолчанию строго контролируем только :app и :miniapp (можно переопределить свойством)
val jsLockStrictProjectsRaw: Set<String> = providers
    .gradleProperty("jsLockStrictProjects")
    .orElse(":app,:miniapp")
    .map { raw -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
    .get()
val jsLockStrictProjects: Set<String> = jsLockStrictProjectsRaw.map { p ->
    if (p.startsWith(":")) p else ":$p"
}.toSet()
// Только реально существующие в этом билде
val existingStrictProjects: Set<String> = jsLockStrictProjects
    .mapNotNull { path -> rootProject.findProject(path)?.path }
    .toSet()

// Преобразуем запрошенные задачи в абсолютные пути вида ":p:r:o:j:task"
val requestedTaskPathsProvider = providers.provider {
    gradle.startParameter.taskNames.map { name -> if (name.startsWith(":")) name else ":$name" }
}
val bareRequestedNamesProvider = providers.provider {
    gradle.startParameter.taskNames.filter { !it.startsWith(":") }
}

// Считаем, относится ли задача к packaging/publish
fun isPackagingOrPublish(path: String): Boolean {
    if (path == ":build" || path.endsWith(":build")) return true
    if (path.endsWith(":assemble")) return true
    if (path.endsWith(":fatJar")) return true
    if (path.endsWith(":shadowJar")) return true
    if (path.endsWith(":jar")) return true
    if (path.endsWith(":installDist")) return true
    if (path.endsWith(":distZip")) return true
    if (path.endsWith(":distTar")) return true
    if (path.endsWith(":publish") || path.contains(":publish")) return true
    if (path.endsWith(":publishToMavenLocal")) return true
    return false
}

// Принадлежит ли задача одному из строгих проектов
fun belongsToStrictProject(path: String): Boolean =
    existingStrictProjects.any { strict ->
        // точное совпадение проекта + двоеточие перед именем задачи
        strict.isNotEmpty() && path.startsWith("$strict:")
    }

val shouldEnforceByRequest = requestedTaskPathsProvider.map { req ->
    req.any { p -> isPackagingOrPublish(p) && belongsToStrictProject(p) }
}
val enforceByBareNames = bareRequestedNamesProvider.map { bare ->
    bare.any { it in barePackagingOrPublishNames }
}

tasks.matching { it.name == "kotlinStoreYarnLock" }.configureEach {
    onlyIf {
        if (skipJsLockCheck) {
            logger.note("Skipping kotlinStoreYarnLock: -PskipJsLockCheck=true")
            return@onlyIf false
        }
        if (forceJsLockCheck) {
            logger.note("Running kotlinStoreYarnLock: -PforceJsLockCheck=true")
            return@onlyIf true
        }
        val enforceBare = enforceByBareNames.get()
        if (enforceBare) {
            val bare = bareRequestedNamesProvider.get()
            if (existingStrictProjects.isEmpty()) {
                logger.note(
                    "Skipping kotlinStoreYarnLock: bare packaging/publish requested, " +
                        "but no strict projects present in this build (requestedBare=${bare.joinToString()}, strict=${jsLockStrictProjects.joinToString()})"
                )
                return@onlyIf false
            } else {
                logger.note(
                    "Running kotlinStoreYarnLock: bare packaging/publish requested " +
                        "(requestedBare=${bare.joinToString()}, strictEffective=${existingStrictProjects.joinToString()})"
                )
                return@onlyIf true
            }
        }
        val req = requestedTaskPathsProvider.get()
        val enforce = shouldEnforceByRequest.get()
        if (!enforce) {
            logger.note("Skipping kotlinStoreYarnLock: no packaging/publish tasks for strict projects (requested=${req.joinToString()}, strict=${jsLockStrictProjects.joinToString()})")
        } else {
            logger.note("Running kotlinStoreYarnLock: packaging/publish tasks for strict projects (requested=${req.joinToString()}, strict=${jsLockStrictProjects.joinToString()})")
        }
        enforce
    }
}
