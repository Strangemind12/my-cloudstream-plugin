// v1.13
import java.util.Properties

version = 13

android {
    namespace = "com.phisher98"
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val propFile = project.rootProject.file("local.properties")
        val properties = Properties()
        
        // v1.13: 파일이 없을 경우 에러 대신 빈 값 처리 (CI 환경 대응)
        val tmdbApiKey = if (propFile.exists()) {
            propFile.inputStream().use { properties.load(it) }
            properties.getProperty("TMDB_API") ?: ""
        } else {
            System.getenv("TMDB_API") ?: ""
        }
        
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
    tvTypes = listOf("TvSeries", "Movie")
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/master/StremioX/icon.png"
}
