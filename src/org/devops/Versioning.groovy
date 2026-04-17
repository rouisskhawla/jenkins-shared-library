package org.devops

class Versioning {

    static String generate(String imageName, String branch, String buildNumber, String gitCommit) {

        def parts = imageName.split('/')
        def org = parts[0]
        def repo = parts[1]

        // Docker Hub API call
        def url = "https://hub.docker.com/v2/repositories/${org}/${repo}/tags/?page_size=100"

        def connection = new URL(url).openConnection()
        connection.setRequestProperty("Accept", "application/json")

        def response = connection.inputStream.getText("UTF-8")

        def json = new groovy.json.JsonSlurper().parseText(response)

        def tags = json.results.collect { it.name }
                .findAll { it ==~ /^[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9]+)?$/ }

        def baseVersion = tags ? tags.sort(false).last() : "0.0.0"

        def versionNumber = baseVersion.split('-')[0]
        def (major, minor, patch) = versionNumber.tokenize('.').collect { it as int }

        patch = patch + 1

        def version

        if (branch == "dev") {
            version = "${major}.${minor}.${patch}-dev"
        } else if (branch == "main") {
            version = "${major}.${minor}.${patch}"
        } else {
            throw new RuntimeException("Unsupported branch: ${branch}")
        }

        return version
    }
}