package com.example.pcmaker

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

data class PCComponent(
    val name: String,
    val price: Int,
    val category: String,
    val wattage: Int = 0,
    val socket: String? = null
)

@Entity(tableName = "selected_components")
data class PCComponentEntity(
    @PrimaryKey val category: String,
    val name: String,
    val price: Int,
    val wattage: Int,
    val socket: String?
)

@Entity(tableName = "saved_builds")
data class SavedBuildEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val buildName: String,
    val componentsJson: String, // Storing as JSON for simplicity in "Previous Builds" list
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface BuildDao {
    @Query("SELECT * FROM selected_components")
    fun getSelectedComponents(): Flow<List<PCComponentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComponent(component: PCComponentEntity)

    @Query("DELETE FROM selected_components WHERE category = :category")
    suspend fun deleteComponent(category: String)

    @Query("DELETE FROM selected_components")
    suspend fun clearBuild()

    @Insert
    suspend fun saveFullBuild(build: SavedBuildEntity)

    @Query("SELECT * FROM saved_builds ORDER BY timestamp DESC")
    fun getAllSavedBuilds(): Flow<List<SavedBuildEntity>>

    @Delete
    suspend fun deleteSavedBuild(build: SavedBuildEntity)
}

@Database(entities = [PCComponentEntity::class, SavedBuildEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun buildDao(): BuildDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pcmaker_database"
                )
                .fallbackToDestructiveMigration() // For simplicity during development
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

interface PCComponentApi {
    @GET("components.json") // This would be the actual endpoint
    suspend fun getComponents(): List<PCComponent>
}

object RetrofitClient {
    private const val BASE_URL =
        "https://raw.githubusercontent.com/omkarzende/pc-api/refs/heads/main/"

    val api: PCComponentApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PCComponentApi::class.java)
    }
}

val sampleComponents = listOf(
    PCComponent("Intel Core i9-13900K", 589, "CPU", wattage = 253, socket = "LGA1700"),
    PCComponent("AMD Ryzen 9 7950X", 549, "CPU", wattage = 170, socket = "AM5"),
    PCComponent("Intel Core i7-13700K", 409, "CPU", wattage = 253, socket = "LGA1700"),
    PCComponent("NVIDIA RTX 4090", 1599, "GPU", wattage = 450),
    PCComponent("NVIDIA RTX 4080", 1199, "GPU", wattage = 320),
    PCComponent("AMD RX 7900 XTX", 999, "GPU", wattage = 355),
    PCComponent("32GB DDR5-6000", 150, "RAM"),
    PCComponent("16GB DDR5-5200", 80, "RAM"),
    PCComponent("2TB NVMe SSD", 140, "Storage"),
    PCComponent("1TB NVMe SSD", 80, "Storage"),
    PCComponent("850W Gold PSU", 120, "PSU", wattage = 850),
    PCComponent("1000W Platinum PSU", 200, "PSU", wattage = 1000),
    PCComponent("Z790 Motherboard", 250, "Motherboard", socket = "LGA1700"),
    PCComponent("X670 Motherboard", 280, "Motherboard", socket = "AM5")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var components by remember { mutableStateOf(sampleComponents) }

                    LaunchedEffect(Unit) {
                        try {
                            val fetched = RetrofitClient.api.getComponents()
                            if (fetched.isNotEmpty()) {
                                components = fetched
                            }
                        } catch (e: Exception) {
                            // Fallback to sampleComponents on error
                        }
                    }

                    PCBuilderApp(components)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PCBuilderApp(components: List<PCComponent>) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val dao = database.buildDao()
    val scope = rememberCoroutineScope()

    val savedComponents by dao.getSelectedComponents().collectAsState(initial = emptyList())

    val selectedComponents = remember(savedComponents) {
        savedComponents.associate {
            it.category to PCComponent(it.name, it.price, it.category, it.wattage, it.socket)
        }
    }

    val categories = listOf("CPU", "GPU", "RAM", "Storage", "PSU", "Motherboard")

    val totalCost = selectedComponents.values.sumOf { it.price }
    val consumedWattage =
        selectedComponents.values.filter { it.category != "PSU" }.sumOf { it.wattage }
    val psuCapacity = selectedComponents["PSU"]?.wattage ?: 0

    val cpu = selectedComponents["CPU"]
    val mobo = selectedComponents["Motherboard"]
    val socketMismatch = cpu != null && mobo != null && cpu.socket != mobo.socket
    val psuInsufficient = psuCapacity in 1 until consumedWattage

    var showPreviousBuilds by remember { mutableStateOf(false) }
    val savedBuilds by dao.getAllSavedBuilds().collectAsState(initial = emptyList())
    val snackbarHostState = remember { SnackbarHostState() }
    val gson = remember { Gson() }

    if (showPreviousBuilds) {
        AlertDialog(
            onDismissRequest = { showPreviousBuilds = false },
            title = { Text("Previous Builds") },
            text = {
                if (savedBuilds.isEmpty()) {
                    Text("No saved builds yet.")
                } else {
                    Box(modifier = Modifier.heightIn(max = 400.dp)) {
                        LazyColumn {
                            items(savedBuilds) { build ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            scope.launch {
                                                dao.clearBuild()
                                                val type = object : TypeToken<List<PCComponentEntity>>() {}.type
                                                val components: List<PCComponentEntity> = gson.fromJson(build.componentsJson, type)
                                                components.forEach { dao.insertComponent(it) }
                                                showPreviousBuilds = false
                                                snackbarHostState.showSnackbar("Loaded build: ${build.buildName}")
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(build.buildName, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "Timestamp: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(build.timestamp))}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        IconButton(onClick = {
                                            scope.launch {
                                                dao.deleteSavedBuild(build)
                                                snackbarHostState.showSnackbar("Deleted build: ${build.buildName}")
                                            }
                                        }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete build",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPreviousBuilds = false }) {
                    Text("Close")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("PC Configurator") },
                actions = {
                    if (selectedComponents.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch { dao.clearBuild() }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear All")
                        }
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                modifier = Modifier.height(140.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Total Cost: $$totalCost",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Wattage: $consumedWattage / ${if (psuCapacity > 0) "$psuCapacity W" else "N/A"}",
                            color = if (psuInsufficient) Color.Red else Color.Unspecified
                        )
                    }
                    if (socketMismatch || psuInsufficient) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color.Red,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                if (socketMismatch) " Socket Mismatch!" else " PSU Wattage Insufficient!",
                                color = Color.Red,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(
                            onClick = {
                                val missingCategories = categories.filter { it !in selectedComponents }
                                if (missingCategories.isEmpty()) {
                                    if (socketMismatch || psuInsufficient) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Please fix compatibility issues before saving.")
                                        }
                                    } else {
                                        scope.launch {
                                            // Save to historical builds
                                            val buildName = "Build ${System.currentTimeMillis() % 10000}" // Simple unique name
                                            val componentsList = savedComponents // This is the list of PCComponentEntity from the collectAsState
                                            val json = gson.toJson(componentsList)
                                            dao.saveFullBuild(SavedBuildEntity(buildName = buildName, componentsJson = json))

                                            snackbarHostState.showSnackbar("Build saved to history as $buildName!")
                                        }
                                    }
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Missing: ${missingCategories.joinToString(", ")}")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save")
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        OutlinedButton(
                            onClick = { showPreviousBuilds = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Previous Builds")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        val listState = rememberLazyListState()

        Box(modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(categories) { category ->
                    CategorySection(
                        category = category,
                        allComponents = components,
                        selectedComponent = selectedComponents[category],
                        onComponentSelected = { component ->
                            scope.launch {
                                dao.insertComponent(
                                    PCComponentEntity(
                                        category = category,
                                        name = component.name,
                                        price = component.price,
                                        wattage = component.wattage,
                                        socket = component.socket
                                    )
                                )
                            }
                        },
                        onComponentRemoved = {
                            scope.launch { dao.deleteComponent(category) }
                        }
                    )
                }
            }

            // Custom Scrollbar
            val density = LocalDensity.current
            if (components.isNotEmpty()) {
                Canvas(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(8.dp)
                        .padding(end = 2.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    val layoutInfo = listState.layoutInfo
                    val totalItemsCount = layoutInfo.totalItemsCount
                    val visibleItemsCount = layoutInfo.visibleItemsInfo.size

                    if (totalItemsCount > visibleItemsCount) {
                        val canvasHeight = size.height
                        val firstVisibleItemIndex = listState.firstVisibleItemIndex
                        val firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset

                        // Approximate position
                        val viewportHeight =
                            layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                        val totalHeightEstimate =
                            (layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0) * totalItemsCount

                        if (totalHeightEstimate > 0) {
                            val thumbHeightPx =
                                (canvasHeight * viewportHeight / totalHeightEstimate).coerceIn(
                                    with(density) { 40.dp.toPx() }, canvasHeight
                                )
                            val scrollProgress =
                                (firstVisibleItemIndex * (layoutInfo.visibleItemsInfo.firstOrNull()?.size
                                    ?: 0) + firstVisibleItemScrollOffset).toFloat() / totalHeightEstimate
                            val thumbOffset = (canvasHeight - thumbHeightPx) * scrollProgress

                            drawRoundRect(
                                color = Color.Gray.copy(alpha = 0.8f), // Increased alpha for better visibility
                                topLeft = Offset(0f, thumbOffset),
                                size = Size(size.width, thumbHeightPx),
                                cornerRadius = CornerRadius(with(density) { 4.dp.toPx() })
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CategorySection(
    category: String,
    allComponents: List<PCComponent>,
    selectedComponent: PCComponent?,
    onComponentSelected: (PCComponent) -> Unit,
    onComponentRemoved: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (selectedComponent != null) {
                    Text(text = selectedComponent.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "$${selectedComponent.price}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (selectedComponent.socket != null) {
                        Text(
                            text = "Socket: ${selectedComponent.socket}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (selectedComponent.wattage > 0) {
                        Text(
                            text = "Wattage: ${selectedComponent.wattage}W",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Text(
                        text = "Not selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            if (selectedComponent != null) {
                IconButton(onClick = onComponentRemoved) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove $category",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Select $category") },
            text = {
                val options = allComponents.filter { it.category == category }
                Box(modifier = Modifier.heightIn(max = 400.dp)) {
                    LazyColumn {
                        items(options) { component ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onComponentSelected(component)
                                        showDialog = false
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(component.name)
                                    Text(
                                        "$${component.price}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (selectedComponent == component) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
