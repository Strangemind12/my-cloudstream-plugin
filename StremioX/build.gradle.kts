// v1.12
import java.util.Properties

// use an integer for version numbers
version = 13

android {
    // v1.12: namespace를 명시하여 BuildConfig 패키지를 고정함
    namespace = "com.phisher98"
    
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val propFile = project.rootProject.file("local.properties")
        val properties = Properties()
        
        // v1.12: 파일이 있을 때만 읽고, 없으면 환경변수나 빈 값을 사용함
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
