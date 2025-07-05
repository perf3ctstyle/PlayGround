import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.AppsV1Api
import io.kubernetes.client.util.Config
import io.kubernetes.client.openapi.models.V1Deployment
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1RollingUpdateDeployment
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime

@Serializable
data class VaultMetadataResponse(
    val data: VaultMetadataData? = null,
    val errors: List<String>? = null
)

@Serializable
data class VaultMetadataData(
    val current_version: Int? = null
)

class VaultSecretOperator(
    private val httpClient: HttpClient,
    private val k8sAppsApi: AppsV1Api,
    private val vaultAddress: String,
    private val vaultToken: String,
    private val secretPath: String,
    private val deploymentName: String,
    private val namespace: String
) {
    private var currentVersion: Int = 1
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun startMonitoring() {
        println("Starting to monitor secret at path: $secretPath")

        while (true) {
            try {
                val metadata = getSecretMetadata()
                val newVersion = metadata.data?.current_version ?: 0

                if (newVersion > currentVersion) {
                    println("Secret version changed from $currentVersion to $newVersion. Triggering deployment restart.")
                    restartDeployment()
                    currentVersion = newVersion
                }
            } catch (e: Exception) {
                println("Error monitoring secret: ${e.message}")
            }

            delay(5000)
        }
    }

    private fun getSecretMetadata(): VaultMetadataResponse {
        val url = "$vaultAddress/v1/${secretPath}/metadata/creds"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("X-Vault-Token", vaultToken)
            .header("Content-Type", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Vault request failed with status ${response.statusCode()}: ${response.body()}")
        }

        return json.decodeFromString(response.body())
    }

    private fun restartDeployment() {
        try {
            // Change the annotation in a pod to trigger a restart
            val currentDateTime = System.currentTimeMillis()
            val patch =
                "[{\"op\":\"add\",\"path\":\"/spec/template/metadata/annotations\",\"value\":{\"vault-watcher/restartedAt\":\"$currentDateTime\"}}]"
            val v1Patch = V1Patch(patch)

            k8sAppsApi.patchNamespacedDeployment(
                deploymentName,
                namespace,
                v1Patch,
                null,
                null,
                null,
                null,
                null
            )

            println("Successfully triggered restart of deployment $deploymentName")
        } catch (e: ApiException) {
            println("Error restarting deployment: ${e.responseBody}")
        }
    }
}

fun main() = runBlocking {
    val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    val apiClient: ApiClient = try {
        Config.defaultClient()
    } catch (e: Exception) {
        println("Error creating Kubernetes client: ${e.message}")
        return@runBlocking
    }

    Configuration.setDefaultApiClient(apiClient)
    val appsApi = AppsV1Api(apiClient)

    val operator = VaultSecretOperator(
        httpClient = httpClient,
        k8sAppsApi = appsApi,
        vaultAddress = "http://localhost:8081", // Update with your Vault address
        vaultToken = "",    // Update with your Vault token
        secretPath = "secret/database",
        deploymentName = "nginx",
        namespace = "vault"
    )

    operator.startMonitoring()
}