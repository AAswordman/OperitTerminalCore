package com.ai.assistance.operit.terminal.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.terminal.TerminalManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.collect

enum class InstallStatus {
    CHECKING,
    INSTALLED,
    NOT_INSTALLED
}

data class PackageItem(
    val id: String,
    val name: String,
    val command: String,
    val description: String = ""
)

data class PackageCategory(
    val id: String,
    val name: String,
    val description: String,
    val packages: List<PackageItem>
)

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    onSetup: (List<String>) -> Unit
) {
    val packageCategories = remember {
        listOf(
            PackageCategory(
                id = "nodejs",
                name = "Node.js 环境",
                description = "Node.js 和前端开发环境",
                packages = listOf(
                    PackageItem("nodejs", "Node.js", "nodejs", "JavaScript 运行时"),
                    PackageItem("pnpm", "PNPM", "typescript", "快速的包管理器和 TypeScript")
                )
            ),
            PackageCategory(
                id = "python",
                name = "Python 环境",
                description = "Python 开发环境",
                packages = listOf(
                    PackageItem("python-is-python3", "Python 链接", "python-is-python3", "将python命令链接到python3"),
                    PackageItem("python3-venv", "虚拟环境", "python3-venv", "Python 虚拟环境支持"),
                    PackageItem("python3-pip", "Pip", "python3-pip", "Python 包管理器"),
                    PackageItem("uv", "uv", "pipx install uv", "一个用 Rust 编写的极速 Python 包安装器")
                )
            ),
            PackageCategory(
                id = "java", 
                name = "Java 环境",
                description = "Java 开发环境",
                packages = listOf(
                    PackageItem("openjdk-17", "OpenJDK 17", "openjdk-17-jdk", "Java 17 开发环境")
                )
            ),
            PackageCategory(
                id = "rust",
                name = "Rust (Cargo) 环境",
                description = "Rust 开发环境和包管理器",
                packages = listOf(
                    PackageItem("rust", "Rust & Cargo", "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y", "通过 rustup 安装 Rust 工具链")
                )
            ),
            PackageCategory(
                id = "go",
                name = "Go 环境",
                description = "Go 语言开发环境",
                packages = listOf(
                    PackageItem("go", "Go", "golang-go", "Go 编程语言")
                )
            )
        )
    }

    // 跟踪每个分类的展开状态
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }
    
    // 跟踪选中的包
    val selectedPackages = remember { mutableStateMapOf<String, Boolean>() }
    
    // 跟踪每个分类的全选状态
    val categorySelectAll = remember { mutableStateMapOf<String, Boolean>() }
    
    // 新增：跟踪包的安装状态
    val packageStatus = remember { mutableStateMapOf<String, InstallStatus>() }
    val context = LocalContext.current
    val terminalManager = remember(context) { TerminalManager.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()
    var checkSessionId by remember { mutableStateOf<String?>(null) }

    // 创建一个用于检查的会话，并在Composable销毁时关闭它
    DisposableEffect(terminalManager) {
        val job = coroutineScope.launch {
            try {
                val session = terminalManager.createNewSession("setup-check")
                checkSessionId = session.id
                Log.d("SetupScreen", "Created check session: ${session.id}")
            } catch (e: Exception) {
                Log.e("SetupScreen", "Failed to create check session", e)
            }
        }

        onDispose {
            job.cancel()
            checkSessionId?.let {
                Log.d("SetupScreen", "Closing check session $it")
                terminalManager.closeSession(it)
            }
        }
    }

    // 当会话准备好后，开始检查包状态
    LaunchedEffect(checkSessionId) {
        val sessionId = checkSessionId ?: return@LaunchedEffect

        // 初始化所有包为检查中状态
        val allPackages = packageCategories.flatMap { it.packages }
        allPackages.forEach { pkg ->
            packageStatus[pkg.id] = InstallStatus.CHECKING
        }

        // 并发检查所有包
        allPackages.forEach { pkg ->
            launch {
                val isInstalled = checkPackageInstalled(terminalManager, sessionId, pkg, this)
                if (isInstalled) {
                    packageStatus[pkg.id] = InstallStatus.INSTALLED
                    selectedPackages[pkg.id] = true
                } else {
                    packageStatus[pkg.id] = InstallStatus.NOT_INSTALLED
                }

                // 检查是否需要更新分类的全选状态
                val category = packageCategories.find { c -> c.packages.any { it.id == pkg.id } }
                category?.let { cat ->
                    val allInCategoryFinishedChecking = cat.packages.all { p -> packageStatus[p.id] != InstallStatus.CHECKING }
                    if (allInCategoryFinishedChecking) {
                        val allInCategorySelected = cat.packages.all { p -> selectedPackages[p.id] == true }
                        categorySelectAll[cat.id] = allInCategorySelected
                    }
                }
            }
        }
    }

    var showSetupDialog by remember { mutableStateOf(false) }
    val commandsToRun = remember { mutableStateOf<List<String>>(emptyList()) }

    if (showSetupDialog) {
        AlertDialog(
            onDismissRequest = { showSetupDialog = false },
            title = { Text("温馨提示") },
            text = { Text("即将开始环境配置，这可能需要一些时间。请尽量保持应用在前台或小窗运行以确保配置顺利进行。\n\n如果配置意外中断，不必担心。下次回到本页面再次开始配置时，会自动从上次的进度继续。") },
            confirmButton = {
                Button(
                    onClick = {
                        showSetupDialog = false
                        // 在开始设置前，显式关闭检查会话
                        checkSessionId?.let { sid ->
                            Log.d("SetupScreen", "Closing check session $sid before starting setup.")
                            terminalManager.closeSession(sid)
                            checkSessionId = null // 防止 onDispose 重复关闭
                        }
                        onSetup(commandsToRun.value)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400))
                ) {
                    Text("我明白了", color = Color.White)
                }
            },
            dismissButton = {
                Button(
                    onClick = { showSetupDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A4A))
                ) {
                    Text("取消", color = Color.White)
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.Gray
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = "环境配置",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Text(
            text = "选择需要安装的开发环境和工具（支持并行安装）",
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // 包分类列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(packageCategories) { category ->
                CategoryCard(
                    category = category,
                    isExpanded = expandedCategories[category.id] ?: false,
                    onExpandToggle = { expandedCategories[category.id] = !expandedCategories.getOrDefault(category.id, false) },
                    selectedPackages = selectedPackages,
                    categorySelectAll = categorySelectAll,
                    packageStatus = packageStatus
                )
            }
        }

        // 底部按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A4A))
            ) {
                Text("跳过", color = Color.White)
            }
            
            Button(
                onClick = {
                    val commands = mutableListOf<String>()
                    
                    // 系统修复（串行）
                    commands.add("dpkg --configure -a")
                    commands.add("apt install -f -y")

                    // 删除残留的锁文件，防止apt-fast安装或使用失败
                    commands.add("rm -f /tmp/apt-fast.lock")

                    // 安装 apt-fast 以实现多线程下载
                    commands.add("apt update -y")
                    commands.add("DEBIAN_FRONTEND=noninteractive apt install -y software-properties-common")
                    commands.add("add-apt-repository ppa:apt-fast/stable -y")
                    commands.add("apt update -y")
                    commands.add("DEBIAN_FRONTEND=noninteractive apt install -y apt-fast")

                    // 使用 apt-fast 进行系统升级
                    commands.add("apt-fast upgrade -y")
                    
                    // 收集选中的包
                    val selectedAptPackages = mutableListOf<String>()
                    val selectedNpmPackages = mutableListOf<String>()
                    val selectedCustomCommands = mutableListOf<String>()
                    
                    packageCategories.forEach { category ->
                        category.packages.forEach { pkg ->
                            if (selectedPackages[pkg.id] == true && packageStatus[pkg.id] != InstallStatus.INSTALLED) {
                                // 根据分类和包ID判断包管理器
                                if (pkg.id == "rust" || pkg.id == "uv") {
                                    selectedCustomCommands.add(pkg.command)
                                } else if (category.id == "nodejs" && pkg.id != "nodejs") {
                                    selectedNpmPackages.add(pkg.command)
                                } else {
                                    selectedAptPackages.add(pkg.command)
                                }
                            }
                        }
                    }

                    // 添加 pipx 作为 uv 的依赖
                    if (selectedPackages.getOrDefault("uv", false) && packageStatus["uv"] != InstallStatus.INSTALLED) {
                        selectedAptPackages.add("pipx")
                    }

                    // 首先安装所有依赖包
                    val allAptDeps = mutableSetOf<String>()
                    
                    // 添加自定义命令的依赖
                    if (selectedCustomCommands.isNotEmpty()) {
                        if (selectedPackages.getOrDefault("rust", false)) {
                            allAptDeps.add("curl")
                            allAptDeps.add("build-essential")
                        }
                    }
                    
                    // 添加选中的 apt 包
                    allAptDeps.addAll(selectedAptPackages)
                    
                    // 使用 apt-fast 安装所有 apt 包和依赖
                    if (allAptDeps.isNotEmpty()) {
                        commands.add("apt-fast install -y ${allAptDeps.joinToString(" ")}")
                    }
                    
                    // 然后运行自定义命令（如安装 rust, uv 等）
                    if (selectedCustomCommands.isNotEmpty()) {
                        commands.addAll(selectedCustomCommands)

                        // 如果安装了 uv，则需要确保 pipx 路径可用
                        if (selectedPackages.getOrDefault("uv", false)) {
                            commands.add("pipx ensurepath")
                            commands.add("source ~/.profile")
                        }
                    }
                    
                    // 使用 apt-fast 安装 npm，然后并行安装 NPM 包
                    if (selectedNpmPackages.isNotEmpty()) {
                        commands.add("apt-fast install -y npm")
                        // 更换为淘宝源
                        commands.add("npm config set registry https://registry.npmmirror.com/")
                        // 安装pnpm
                        commands.add("npm install -g pnpm")
                        // 使用 pnpm 安装其他包
                        commands.add("pnpm add -g ${selectedNpmPackages.joinToString(" ")}")
                    }
                    
                    commandsToRun.value = commands
                    showSetupDialog = true
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400))
            ) {
                Text("开始配置", color = Color.White)
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: PackageCategory,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    selectedPackages: MutableMap<String, Boolean>,
    categorySelectAll: MutableMap<String, Boolean>,
    packageStatus: Map<String, InstallStatus>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 分类标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = category.name,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (category.id == "nodejs" || category.id == "python") {
                            Text(
                                text = "(Operit 必须)",
                                color = Color(0xFFFFA500), // Orange color
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    Text(
                        text = category.description,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                
                // 全选按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Checkbox(
                        checked = categorySelectAll[category.id] ?: false,
                        onCheckedChange = { selectAll ->
                            categorySelectAll[category.id] = selectAll
                            category.packages.forEach { pkg ->
                                if (packageStatus[pkg.id] != InstallStatus.INSTALLED) {
                                    selectedPackages[pkg.id] = selectAll
                                }
                            }
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF006400),
                            uncheckedColor = Color.Gray
                        )
                    )
                    Text(
                        text = "全选",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                
                // 展开/收起图标
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = Color.White
                )
            }
            
            // 包列表（可展开）
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                
                category.packages.forEach { pkg ->
                    PackageItem(
                        packageItem = pkg,
                        isSelected = selectedPackages[pkg.id] ?: false,
                        onSelectionChange = { selected ->
                            selectedPackages[pkg.id] = selected
                            // 检查是否需要更新全选状态
                            val allSelectedAfterChange = category.packages.all { p ->
                                selectedPackages[p.id] == true
                            }
                            categorySelectAll[category.id] = allSelectedAfterChange
                        },
                        status = packageStatus[pkg.id] ?: InstallStatus.NOT_INSTALLED
                    )
                }
            }
        }
    }
}

@Composable
private fun PackageItem(
    packageItem: PackageItem,
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    status: InstallStatus
) {
    val isInstalled = status == InstallStatus.INSTALLED
    val isChecking = status == InstallStatus.CHECKING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isInstalled) { onSelectionChange(!isSelected) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Checkbox(
                checked = isSelected || isInstalled,
                onCheckedChange = onSelectionChange,
                enabled = !isInstalled,
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF006400),
                    uncheckedColor = Color.Gray,
                    disabledCheckedColor = Color(0xFF006400).copy(alpha = 0.5f)
                )
            )
        }
        
        Column(
            modifier = Modifier.padding(start = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = packageItem.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                if (isInstalled) {
                    Text(
                        text = " (已安装)",
                        color = Color.Green.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            if (packageItem.description.isNotEmpty()) {
                Text(
                    text = packageItem.description,
                    color = Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun checkPackageInstalled(
    terminalManager: TerminalManager,
    sessionId: String,
    pkg: PackageItem,
    scope: CoroutineScope
): Boolean {
    val command: String = when (pkg.id) {
        "rust" -> "command -v rustc"
        "uv" -> "command -v uv"
        "pnpm" -> "test -f \"$(npm prefix -g)/bin/pnpm\" && echo FOUND_PNPM"
        "go" -> "command -v go"
        else -> "dpkg -s ${pkg.command.split(" ").first()}"
    }

    val output = executeCommandAndGetOutput(terminalManager, sessionId, command, scope)
    if (output == null) return false // 超时或错误

    return when (pkg.id) {
        "rust", "uv", "go" -> output.isNotBlank() && !output.contains("not found")
        "pnpm" -> output.contains("FOUND_PNPM")
        else -> output.contains("Status: install ok installed")
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun executeCommandAndGetOutput(
    terminalManager: TerminalManager,
    sessionId: String,
    command: String,
    scope: CoroutineScope
): String? {
    val deferred = CompletableDeferred<String>()
    val output = StringBuilder()
    val commandId = UUID.randomUUID().toString()
    val collectorReady = CompletableDeferred<Unit>()

    val job = scope.launch {
        terminalManager.commandExecutionEvents
            .filter { it.sessionId == sessionId && it.commandId == commandId }
            .onStart { collectorReady.complete(Unit) }
            .collect { event ->
                output.append(event.outputChunk)
                if (event.isCompleted) {
                    if (!deferred.isCompleted) {
                        deferred.complete(output.toString())
                    }
                }
            }
    }

    collectorReady.await()
    terminalManager.switchToSession(sessionId)
    terminalManager.sendCommand(command, commandId)

    val result = withTimeoutOrNull(15000L) { // 15s timeout
        deferred.await()
    }
    
    job.cancel()
    return result
} 