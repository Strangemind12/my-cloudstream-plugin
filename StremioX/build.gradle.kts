// v1.10
import java.util.Properties // org.jetbrains.kotlin... 대신 표준 java.util 사용

// use an integer for version numbers
version = 13

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val propFile = project.rootProject.file("local.properties")
        val tmdbApiKey = if (propFile.exists()) {
            val properties = Properties()
            propFile.inputStream().use { properties.load(it) }
            properties.getProperty("TMDB_API") ?: ""
        } else {
            System.getenv("TMDB_API") ?: ""
        }
        
        android.buildFeatures.buildConfig = true
        buildConfigField("String", "TMDB_API", "\"$tmdbApiKey\"")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    language = "en"
    description = "[!] Requires Setup \n- StremioX allows you to use stream addons \n- StremioC allows you to use catalog addons"
    authors = listOf("Hexated,phisher98")
    status = 1 
    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/master/StremioX/icon.png"
}
