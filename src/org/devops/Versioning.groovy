package org.devops

class Versioning {

    static String generate(String imageName, String branch, String buildNumber, String gitCommit) {

        def shortCommit = gitCommit?.take(7) ?: "unknown"

        def baseVersion = "1.0.0" 

        def (major, minor, patch) = baseVersion.tokenize('.').collect { it as int }

        patch = patch + buildNumber.toInteger()

        if (branch == "dev") {
            return "${major}.${minor}.${patch}-dev-${shortCommit}"
        }

        if (branch == "main") {
            return "${major}.${minor}.${patch}-${shortCommit}"
        }
        else {
            return "${major}.${minor}.${patch}-${branch}-${shortCommit}"
        }

        return "${major}.${minor}.${patch}-${shortCommit}"
    }
}