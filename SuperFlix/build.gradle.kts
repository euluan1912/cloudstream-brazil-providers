version = 1

cloudstream {
    description = "SuperFlix - Filmes e Séries em Português"
    language = "pt-br"
    authors = listOf("lietbr")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://superflix21.lol/assets/logo.png"
}

        // build.gradle para o módulo SuperFlix
plugins {
    id 'com.android.library'
    id 'kotlin-android'
}

android {
    compileSdk 34

    defaultConfig {
        minSdk 21
        targetSdk 34
        
        // DEBUG: Mostrar informações
        println "=== CONFIGURANDO BUILDCONFIG PARA SUPERFLIX ==="
        
        // Tentar obter a chave de várias fontes
        def tmdbApiKey = ""
        
        // 1. Tentar do environment
        if (System.getenv('TMDB_API_KEY')) {
            tmdbApiKey = System.getenv('TMDB_API_KEY')
            println "✅ TMDB_API_KEY obtida do environment"
            println "   Tamanho: ${tmdbApiKey.length()}"
        } 
        // 2. Tentar do gradle.properties
        else if (project.hasProperty('TMDB_API_KEY')) {
            tmdbApiKey = project.property('TMDB_API_KEY')
            println "✅ TMDB_API_KEY obtida do gradle.properties"
            println "   Tamanho: ${tmdbApiKey.length()}"
        }
        // 3. Tentar do local.properties
        else {
            def localProperties = new Properties()
            try {
                localProperties.load(new FileInputStream(rootProject.file("local.properties")))
                tmdbApiKey = localProperties.getProperty('TMDB_API_KEY', '')
                if (tmdbApiKey) {
                    println "✅ TMDB_API_KEY obtida do local.properties"
                    println "   Tamanho: ${tmdbApiKey.length()}"
                } else {
                    println "⚠️  TMDB_API_KEY não encontrada em nenhuma fonte"
                    println "   O plugin funcionará sem dados do TMDB"
                }
            } catch (Exception e) {
                println "⚠️  Erro ao ler local.properties: ${e.message}"
            }
        }
        
        // Configurar no BuildConfig
        buildConfigField "String", "TMDB_API_KEY", "\"$tmdbApiKey\""
        
        if (tmdbApiKey) {
            println "✅ TMDB_API_KEY configurada no BuildConfig"
            println "   Primeiros 8 chars: ${tmdbApiKey.substring(0, Math.min(8, tmdbApiKey.length()))}..."
        } else {
            println "⚠️  AVISO: TMDB_API_KEY será string vazia no BuildConfig"
        }
        
        println "=== FIM CONFIGURAÇÃO ==="
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = '1.8'
    }
}

dependencies {
    implementation "org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version"
    // ... outras dependências
}

// Task de debug personalizada
task printDebugInfo {
    doLast {
        println "=== DEBUG INFO SUPERFLIX ==="
        println "Project dir: ${projectDir}"
        println "Build dir: ${buildDir}"
        
        // Verificar environment
        def envKey = System.getenv('TMDB_API_KEY')
        println "Environment TMDB_API_KEY: ${envKey ? 'DEFINIDA (' + envKey.length() + ' chars)' : 'NÃO DEFINIDA'}"
        
        // Verificar project properties
        def projectKey = project.hasProperty('TMDB_API_KEY') ? project.property('TMDB_API_KEY') : null
        println "Project property TMDB_API_KEY: ${projectKey ? 'DEFINIDA (' + projectKey.length() + ' chars)' : 'NÃO DEFINIDA'}"
        
        // Verificar local.properties
        def localProps = new Properties()
        try {
            localProps.load(new FileInputStream(rootProject.file("local.properties")))
            def localKey = localProps.getProperty('TMDB_API_KEY', '')
            println "Local.properties TMDB_API_KEY: ${localKey ? 'DEFINIDA (' + localKey.length() + ' chars)' : 'NÃO DEFINIDA'}"
        } catch (Exception e) {
            println "Local.properties: ${e.message}"
        }
        
        println "=== FIM DEBUG ==="
    }
}
