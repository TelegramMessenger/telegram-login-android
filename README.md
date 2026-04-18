Telegram offers iOS and Android developers a **native integration SDK** that allows over 1 billion users to seamlessly **sign up and log in** with their Telegram accounts. This native flow leverages the Telegram app installed on the user's device, providing a frictionless, passwordless experience.

> For a general overview of Telegram Login and the web library documentation, please visit [Log In With Telegram](https://core.telegram.org/bots/login).

---

### Prerequisites
* Minimum SDK Version: `API 23` (Android 6.0).
* A registered Telegram Bot via [@BotFather](https://t.me/botfather).

### 1. Installation

Add the GitHub Packages Maven repository to your project-level `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/TelegramMessenger/telegram-login-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_USERNAME")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

> **Note:** A GitHub personal access token with `read:packages` scope is required. You can set `gpr.user` and `gpr.key` in your `~/.gradle/gradle.properties` or use environment variables.

Then add the SDK to your app-level `build.gradle` or `build.gradle.kts` file:

```groovy
dependencies {
    implementation 'org.telegram:login-sdk:1.0.0'
}
```

### 2. Setup in BotFather
Register your Android application via [**@BotFather**](https://t.me/botfather) under **Bot Settings > Login Widget** to obtain your `client_id`.

To ensure security, Telegram verifies the cryptographic signature of your Android app:

* **Package Name**: Your app's application ID (e.g., `com.yourcompany.app`).
* **SHA-256 Fingerprint**: The hash of the keystore used to sign your app. 
  > *Tip: You can obtain this by running `./gradlew signingReport` in your project terminal.*

#### Redirect URI Configuration
You must define where Telegram should redirect the user after login:

* **App Link (Highly Recommended)**: `https://app{appid}-login.tg.dev/tglogin`. This ensures only your verified app can intercept the callback. *(Note: The base domain is automatically generated and does not require manual registration in BotFather).*
* **Custom Scheme (Fallback)**: `yourapp://telegram-login`.

---

### 3. Project Configuration (AndroidManifest.xml)
You must declare an `<intent-filter>` inside the `Activity` designated to handle the login response. This tells the Android OS to route the return URL to your app.

#### Setting up Android App Links
Add the `android:autoVerify="true"` attribute to automatically verify domain ownership based on your SHA-256 fingerprint.

```xml
<activity android:name=".LoginActivity" android:launchMode="singleTask">
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" 
              android:host="app123456-login.tg.dev" 
              android:pathPrefix="/tglogin" />
    </intent-filter>
</activity>
```
> **Note:** Setting `launchMode="singleTask"` or `singleTop` on your callback Activity is highly recommended to prevent creating duplicate activity instances when the user returns from the Telegram app.

---

### 4. SDK Initialization
Initialize the SDK parameters in your `Application` class or the `onCreate` method of your main Activity before initiating a login request.

**Kotlin Example:**
```kotlin
import org.telegram.login.TelegramLogin

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        TelegramLogin.init(
            clientId = "YOUR_BOT_CLIENT_ID",
            redirectUri = "https://app123456-login.tg.dev/tglogin",
            scopes = listOf("profile", "phone")
        )
    }
}
```

---

### 5. Login & Callbacks
To trigger the Telegram login popup, call `startLogin` and pass the current context.

```kotlin
TelegramLogin.startLogin(this@YourActivity)
```

#### Intent Handling
When the user finishes the flow, Telegram launches your configured Activity via an Intent. Override `onNewIntent` (or `onCreate` depending on your launch mode) to catch the URI. As a best practice, verify the host before passing the data to the SDK to ensure you aren't processing unrelated intents.

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    
    intent.data?.let { uri ->
        // Simple check to ensure we only pass Telegram login URLs to the SDK
        if (uri.host == "app123456-login.tg.dev") {
            TelegramLogin.handleLoginResponse(uri, onSuccess = { loginData ->
                val idToken = loginData.idToken
                Log.d("TelegramLogin", "Success! Token: $idToken")
                // Send idToken to your backend for verification
            }, onError = { error ->
                Log.e("TelegramLogin", "Login failed: ${error.message}")
            })
        }
    }
}
```

> **Important:** The Telegram app handles the user consent screen natively. However, the data returned (`idToken`) is a JWT. You **must** validate this token on your server to confirm it was signed by Telegram before establishing a session. Read more about [validating ID tokens](https://core.telegram.org/bots/telegram-login#validating-id-tokens) in the core documentation.