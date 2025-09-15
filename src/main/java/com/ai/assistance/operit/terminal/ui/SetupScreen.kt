package com.ai.assistance.operit.terminal.ui

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
                    PackageItem("pnpm", "PNPM", "pnpm", "快速包管理器"),
                    PackageItem("typescript", "TypeScript", "typescript", "TypeScript 编译器")
                )
            ),
            PackageCategory(
                id = "python",
                name = "Python 环境",
                description = "Python 开发环境",
                packages = listOf(
                    PackageItem("python-is-python3", "Python 链接", "python-is-python3", "将python命令链接到python3"),
                    PackageItem("python3-venv", "虚拟环境", "python3-venv", "Python 虚拟环境支持"),
                    PackageItem("python3-pip", "Pip", "python3-pip", "Python 包管理器")
                )
            ),
            PackageCategory(
                id = "java",
                name = "Java 环境",
                description = "Java 开发环境",
                packages = listOf(
                    PackageItem("openjdk-17", "OpenJDK 17", "openjdk-17-jdk", "Java 17 开发环境")
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
                    isAllSelected = categorySelectAll[category.id] ?: false,
                    onSelectAll = { selectAll ->
                        categorySelectAll[category.id] = selectAll
                        category.packages.forEach { pkg ->
                            selectedPackages[pkg.id] = selectAll
                        }
                    }
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
                    
                    packageCategories.forEach { category ->
                        category.packages.forEach { pkg ->
                            if (selectedPackages[pkg.id] == true) {
                                // 根据分类和包ID判断包管理器
                                if (category.id == "nodejs" && pkg.id != "nodejs") {
                                    selectedNpmPackages.add(pkg.command)
                                } else {
                                    selectedAptPackages.add(pkg.command)
                                }
                            }
                        }
                    }
                    
                    // 使用 apt-fast 并行安装 APT 包
                    if (selectedAptPackages.isNotEmpty()) {
                        commands.add("apt-fast install -y ${selectedAptPackages.joinToString(" ")}")
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
                    
                    // 检查是否有包被选中
                    if (commands.size > 8) { // 8条是基础安装命令
                        onSetup(commands)
                    } else {
                        // 即使没选包也执行基础更新
                        onSetup(commands)
                    }
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
    isAllSelected: Boolean,
    onSelectAll: (Boolean) -> Unit
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
                    Text(
                        text = category.name,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
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
                        checked = isAllSelected,
                        onCheckedChange = onSelectAll,
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
                            val allSelectedAfterChange = category.packages.all { selectedPackages[it.id] ?: false }
                            if (isAllSelected != allSelectedAfterChange) {
                                onSelectAll(allSelectedAfterChange)
                            }
                        }
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
    onSelectionChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectionChange(!isSelected) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = onSelectionChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF006400),
                uncheckedColor = Color.Gray
            )
        )
        
        Column(
            modifier = Modifier.padding(start = 12.dp)
        ) {
            Text(
                text = packageItem.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
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