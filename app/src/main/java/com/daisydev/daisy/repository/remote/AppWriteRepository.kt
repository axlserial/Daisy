package com.daisydev.daisy.repository.remote

import android.content.Context
import android.net.Uri
import com.daisydev.daisy.models.AltName
import com.daisydev.daisy.models.BlogDocumentModel
import com.daisydev.daisy.models.BlogEntry
import com.daisydev.daisy.models.DataPlant
import com.daisydev.daisy.models.toBlogEntry
import com.daisydev.daisy.util.getFilenameFromUri
import com.daisydev.daisy.util.removeAccents
import io.appwrite.Client
import io.appwrite.ID
import io.appwrite.Query
import io.appwrite.extensions.toJson
import io.appwrite.models.File
import io.appwrite.models.InputFile
import io.appwrite.models.Session
import io.appwrite.models.User
import io.appwrite.services.Account
import io.appwrite.services.Databases
import io.appwrite.services.Functions
import io.appwrite.services.Storage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repositorio para todas las operaciones relacionadas con AppWrite.
 */
@Singleton
class AppWriteRepository @Inject constructor(
    private val context: Context,
    private val client: Client,
    private val dispatcher: CoroutineDispatcher
) {
    // -- Services --

    private val account = Account(client)
    private val databases = Databases(client)
    private val storage = Storage(client)
    private val functions = Functions(client)

    // -- Account --

    // Iniciar sesión con email y contraseña
    suspend fun login(email: String, password: String): Session {
        return withContext(dispatcher) {
            account.createEmailSession(email, password)
        }
    }

    // Registrar usuario con usuario, email y contraseña
    suspend fun register(
        email: String, password: String,
        name: String
    ): User<Map<String, Any>> {
        return withContext(dispatcher) {
            account.create(
                userId = ID.unique(),
                email = email,
                password = password,
                name = name
            )
        }
    }

    // Para cerrar sesión
    suspend fun logout() {
        return withContext(dispatcher) {
            account.deleteSession("current")
        }
    }

    // Para obtener la información del usuario
    suspend fun getAccount(): User<Map<String, Any>> {
        return withContext(dispatcher) {
            account.get()
        }
    }

    // Para obtener la sesión actual
    suspend fun isLoggedIn(): Session {
        return withContext(dispatcher) {
            account.getSession("current")
        }
    }

    // -- Storage --

    // Para subir una imagen a AppWrite
    suspend fun uploadImage(image: java.io.File): File {
        return withContext(dispatcher) {
            storage.createFile(
                bucketId = "6476c99ad8636acff4ad",
                fileId = ID.unique(),
                file = InputFile.fromFile(image),
            )
        }
    }

    // Para subir una imagen de blog a AppWrite
    suspend fun uploadBlogImage(imageUri: Uri): File {
        return withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                val mimeType = context.contentResolver.getType(imageUri)
                val filename = getFilenameFromUri(context, imageUri)

                storage.createFile(
                    bucketId = "64875f770336e687328c",
                    fileId = ID.unique(),
                    file = InputFile.fromBytes(
                        bytes = stream.readBytes(),
                        filename = filename!!,
                        mimeType = mimeType!!
                    ),
                )
            } ?: throw IOException("Failed to open InputStream for imageUri: $imageUri")
        }
    }

    suspend fun deleteBlogImage(imageId: String) {
        return withContext(dispatcher) {
            storage.deleteFile(
                bucketId = "64875f770336e687328c",
                fileId = imageId
            )
        }
    }

    // -- Functions --

    // Para reconocer una imagen por medio de Google Cloud Vision Y GPT-3.5
    suspend fun recognizeImage(imageId: String): List<DataPlant> {
        return withContext(Dispatchers.IO) {
            // Ejecutar la función
            val execution = functions.createExecution(
                functionId = "64791401c68219857417",
                data = mapOf(
                    "image" to imageId
                ).toJson(),
                async = true
            )

            // Esperar a que la función termine de ejecutarse
            var executionResult = functions.getExecution(
                functionId = "64791401c68219857417",
                executionId = execution.id
            )

            while (executionResult.status != "completed" && executionResult.status != "failed") {
                Thread.sleep(1000)
                executionResult = functions.getExecution(
                    functionId = "64791401c68219857417",
                    executionId = execution.id
                )
            }

            // Obtener el resultado de la función
            val jsonString = executionResult.response

            // Variable para guardar el resultado
            var result = listOf<DataPlant>()

            // Convertir el resultado a una lista de DataPlant
            val jsonArray = JSONArray(jsonString)

            // Si el resultado no está vacío
            if (jsonArray.length() > 0) {
                val arrRange = 0 until jsonArray.length()

                // Convertir el resultado a una lista de DataPlant
                result = arrRange.map { i ->
                    val jsonObject = jsonArray.getJSONObject(i)

                    val altNamesArr = jsonObject.getJSONArray("alt_names")
                    val altNamesRange = 0 until altNamesArr.length()

                    val altNames: List<AltName> = altNamesRange.map {
                        val alt_name = altNamesArr.getJSONObject(it)
                        AltName(
                            name = alt_name.getString("name")
                        )
                    }

                    DataPlant(
                        plant_name = jsonObject.getString("plant_name"),
                        probability = jsonObject.getDouble("probability"),
                        alt_names = altNames
                    )
                }
            }

            // Regresar el resultado
            result
        }
    }

    // -- Databases --

    // Para listar todos los documentos en la base de datos de AppWrite
    suspend fun listDocuments(): MutableList<BlogEntry> {
        return withContext(dispatcher) {
            databases.listDocuments(
                "64668e42ab469f0dcf8d",
                "647a1828df7b78f0dfb1"
            ).documents.map {
                toBlogEntry(it)
            }.toMutableList()
        }
    }

    // Para listar los documentos que cumplan el filtro dado
    suspend fun listDocumentsWithFilter(filter: String): MutableList<BlogEntry> {
        // Limpia el string con los keywords y lo parte usando de delimitador los espacios
        val keywords = filter.trim().split(" ").map { removeAccents(it.lowercase()) }

        return withContext(dispatcher) {
            databases.listDocuments(
                databaseId = "64668e42ab469f0dcf8d",
                collectionId = "647a1828df7b78f0dfb1"
            ).documents.map {
                toBlogEntry(it)
            }.filter { blogEntry ->

                // convertimos todo a lowercase y se limpia
                val plants = blogEntry.plants.map { removeAccents(it.lowercase().trim()) }
                val symptoms = blogEntry.symptoms.map { removeAccents(it.lowercase().trim()) }

                // Sí existe al menos una coincidencia
                keywords.any { keyword ->
                    plants.any { it.contains(keyword) }
                } || keywords.any { keyword ->
                    symptoms.any { it.contains(keyword) }
                }
            }.toMutableList()
        }
    }

    // Para listar los documentos del usuario logueado
    suspend fun listDocumentsOfUser(userId: String): MutableList<BlogEntry> {
        return withContext(dispatcher) {
            databases.listDocuments(
                databaseId = "64668e42ab469f0dcf8d",
                collectionId = "647a1828df7b78f0dfb1",
                queries = listOf(
                    Query.equal("id_user", listOf(userId))
                )
            ).documents.map {
                toBlogEntry(it)
            }.toMutableList()
        }
    }

    // Para obtener un documento de la base de datos de AppWrite
    suspend fun createDocument(documentModel: BlogDocumentModel) {
        return withContext(dispatcher) {
            databases.createDocument(
                databaseId = "64668e42ab469f0dcf8d",
                collectionId = "647a1828df7b78f0dfb1",
                documentId = ID.unique(),
                data = documentModel.toJson()
            )
        }
    }

    suspend fun updateDocument(docId: String, documentModel: BlogDocumentModel) {
        return withContext(dispatcher) {
            databases.updateDocument(
                databaseId = "64668e42ab469f0dcf8d",
                collectionId = "647a1828df7b78f0dfb1",
                documentId = docId,
                data = documentModel.toJson()
            )
        }
    }

    suspend fun deleteDocument(docId: String) {
        return withContext(dispatcher) {
            databases.deleteDocument(
                databaseId = "64668e42ab469f0dcf8d",
                collectionId = "647a1828df7b78f0dfb1",
                documentId = docId
            )
        }
    }
}