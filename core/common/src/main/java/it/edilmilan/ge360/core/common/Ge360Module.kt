package it.edilmilan.ge360.core.common

data class Ge360Module(
    val id: String,
    val title: String,
    val subtitle: String,
    val enabled: Boolean = false
)
