// Reads an optional Firebase Android config and exposes it as string resources.
//
// The google-services Gradle plugin is deliberately not applied. Firebase's own init provider
// starts FirebaseApp from the four string resources below, so emitting them is enough. With no
// usable config nothing is emitted, the build still works, and the app reports that push is not
// available in this build.
//
// Source, first match wins:
//   1. the file named by env BLOEM_ADMIN_GOOGLE_SERVICES_JSON (CI writes the repository secret
//      BLOEM_ADMIN_GOOGLE_SERVICES_JSON to a temporary file and points this at it);
//   2. app/google-services.json (git-ignored);
//   3. ~/.config/bloem-firebase/google-services.json (the shared local copy, mode 0600).
// Only the client whose package_name is the app's applicationId is used; a config for another
// package is ignored. The real file is never committed and its API key is never printed.

import groovy.json.JsonSlurper

val firebasePackageName = "com.bloemstudios.admin"

fun firebaseConfigFile(): File? {
    System.getenv("BLOEM_ADMIN_GOOGLE_SERVICES_JSON")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    val inApp = File(projectDir, "google-services.json")
    if (inApp.isFile) return inApp
    val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return null
    return File(home, ".config/bloem-firebase/google-services.json").takeIf { it.isFile }
}

fun readFirebaseResValues(): Map<String, String> {
    val file = firebaseConfigFile()?.takeIf { it.isFile } ?: return emptyMap()
    val root = try {
        JsonSlurper().parseText(file.readText()) as? Map<*, *>
    } catch (_: Exception) {
        null
    }
    if (root == null) {
        logger.warn("Firebase config ignored: the file is not valid JSON")
        return emptyMap()
    }
    val info = root["project_info"] as? Map<*, *>
    val client = (root["client"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull {
        val clientInfo = it["client_info"] as? Map<*, *>
        val android = clientInfo?.get("android_client_info") as? Map<*, *>
        android?.get("package_name") == firebasePackageName
    }
    if (info == null || client == null) {
        logger.warn("Firebase config ignored: no client for package $firebasePackageName")
        return emptyMap()
    }
    val safe = Regex("[A-Za-z0-9_:.-]{1,200}")
    val values = linkedMapOf(
        "google_app_id" to ((client["client_info"] as Map<*, *>)["mobilesdk_app_id"] as? String),
        "gcm_defaultSenderId" to (info["project_number"] as? String),
        "google_api_key" to (((client["api_key"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("current_key") as? String),
        "project_id" to (info["project_id"] as? String)
    )
    if (values.values.any { it == null || !safe.matches(it) }) {
        logger.warn("Firebase config ignored: a required value is missing or malformed")
        return emptyMap()
    }
    return values.mapValues { it.value!! }
}

val firebaseResValues = readFirebaseResValues()
extra["firebaseResValues"] = firebaseResValues

tasks.register("printFirebaseConfig") {
    group = "verification"
    description = "Prints whether a usable Firebase config was found (never the API key)."
    val values = firebaseResValues
    doLast {
        if (values.isEmpty()) {
            println("firebase_config=absent")
        } else {
            println("firebase_config=present")
            values.forEach { (key, value) ->
                println(if (key == "google_api_key") "$key=set" else "$key=$value")
            }
        }
    }
}
