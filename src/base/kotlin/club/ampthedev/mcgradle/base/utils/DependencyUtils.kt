package club.ampthedev.mcgradle.base.utils

import com.google.gson.JsonObject
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Dependency

fun shouldIncludeDependency(obj: JsonObject): Boolean {
    val rules = arrayListOf<Rule>()
    if (obj.has("rules")) {
        for (element in obj["rules"].asJsonArray) {
            rules.add(Rule.parse(element.asJsonObject))
        }
    }
    var allowed = rules.isEmpty()
    var disallowEncountered = false
    for (rule in rules) {
        allowed = rule.shouldAllow()
        if (!rule.allow && !rule.matchesCurrent()) {
            if (!disallowEncountered && !allowed) {
                allowed = true
                continue
            }
        }
        if (!rule.allow) {
            disallowEncountered = true
        }
    }
    val classifier = obj.getAsJsonObject("natives")?.get(OS.current().name.toLowerCase())?.asString
    if (classifier != null && obj["downloads"]?.asJsonObject?.getAsJsonObject("classifiers")?.has(classifier) != true) {
        return false // override because mojang is dumb and hasn't included rules for this
    }
    return allowed
}

fun getDependencyData(obj: JsonObject): Triple<String, String, Pair<String?, Boolean>> {
    val classifier = obj.getAsJsonObject("natives")?.get(OS.current().name.toLowerCase())?.asString
    val isNative = classifier != null && obj["downloads"]?.asJsonObject?.getAsJsonObject("classifiers")?.has(classifier) == true
    val downloads = obj.getAsJsonObject("downloads")
    var sha1: String? = null
    var url: String? = null
    var name = obj["name"]?.asString
    if (isNative) {
        name += ":$classifier"
    }
    if (downloads != null) {
        val downloadsObj = if (isNative) {
            obj["downloads"].asJsonObject.getAsJsonObject("classifiers").getAsJsonObject(classifier)
        } else {
            obj["downloads"].asJsonObject.getAsJsonObject("artifact")
        }
        url = downloadsObj?.get("url")?.asString
        sha1 = downloadsObj?.get("sha1")?.asString
    } else {
        name?.let {
            val parts = it.split(":")
            url = "https://libraries.minecraft.net/${parts[0].replace('.', '/')}/${parts[1]}/${parts[2]}"
        }
    }
    return url?.let { u -> name?.let { Triple(it, u, Pair(sha1, isNative)) } } ?: error("Invalid dependency in version JSON")
}
/*
fun getDependencyString(obj: JsonObject): String {
    val isNative = obj.has("natives")
    var it = obj["name"].asString
    if (isNative) {
        val nativesObj = obj.getAsJsonObject("natives")
        it += ":" + nativesObj.get(OS.current().name.toLowerCase()).asString.replace("\${arch}", if (System.getProperty("os.arch").equals("x86", ignoreCase = true)) "32" else "64")
    }
    return it
}*/

fun dependencyEqualsMcDep(obj: JsonObject, dep: Dependency): Boolean {
    val name = obj["name"].asString
    val s = name.split(":")
    return dep.group!!.equals(s[0], true) && dep.name.equals(s[1], true) && dep.version!!.equals(s[2], true)
}

private enum class OS {
    WINDOWS,
    LINUX,
    OSX;

    companion object {
        fun current() = when {
            Os.isFamily(Os.FAMILY_WINDOWS) -> WINDOWS
            Os.isFamily(Os.FAMILY_MAC) -> OSX
            Os.isFamily(Os.FAMILY_UNIX) -> LINUX
            else -> throw GradleException("Unsupported OS")
        }

        fun parseMc(mc: String) = when (mc) {
            "windows" -> WINDOWS
            "linux" -> LINUX
            "osx" -> OSX
            else -> throw GradleException("Unsupported OS")
        }
    }
}

private class Rule(val allow: Boolean, val os: OS?, val arch: String?, val version: Regex?) {
    companion object {
        fun parse(rule: JsonObject): Rule {
            var allow = true
            var os: OS? = null
            var arch: String? = null
            var version: Regex? = null
            if (rule.has("action")) {
                allow = when (rule["action"].asString) {
                    "allow" -> true
                    else -> false
                }
            }
            if (rule.has("os")) {
                val osObj = rule["os"].asJsonObject
                if (osObj.has("name")) {
                    os = OS.parseMc(osObj["name"].asString)
                }
                if (osObj.has("version")) {
                    version = Regex(osObj["version"].asString)
                }
                if (osObj.has("arch")) {
                    arch = osObj["arch"].asString
                }
            }
            return Rule(allow, os, arch, version)
        }
    }

    fun matchesCurrent() = when {
        os != null && OS.current() != os -> false
        arch != null && System.getProperty("os.arch") != arch -> false
        version != null && !version.matches(System.getProperty("os.version")) -> false
        else -> true
    }

    fun shouldAllow() = matchesCurrent() && allow
}