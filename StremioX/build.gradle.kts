// v1.4.
import java.util.Properties

// use an integer for version numbers
version = 13

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val propFile = project.rootProject.file("local.properties")
        println("[v1.4 Debug] Gradle Config - local.properties 파일 존재 여부: ${propFile.exists()}")
        
        val tmdbApiKey = if (propFile.exists()) {
            println("[v1.4 Debug] local.properties 파일에서 TMDB_API 값을 읽어옵니다.")
            val properties = Properties()
            properties.load(propFile.inputStream())
            properties.getProperty("TMDB_API") ?: ""
        } else {
            println("[v1.4 Debug] local.properties 파일이 없습니다. 환경변수 또는 기본값을 사용합니다.")
            System.getenv("TMDB_API") ?: ""
        }

        println("[v1.4 Debug] 설정된 TMDB_API 길이: ${tmdbApiKey.length}")
        
        android.buildFeatures.buildConfig = true
        buildConfigField("String", "TMDB_API", "\"$tmdbApiKey\"")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    language = "en"
   
 // All of these properties are optional, you can safely remove them

     description = "[!] Requires Setup \n- StremioX allows you to use stream addons \n- StremioC allows you to use catalog addons"
     authors = listOf("Hexated,phisher98")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 
// will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/master/StremioX/icon.png"
}
