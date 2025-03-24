package pt.isel.sports

import pt.isel.Pk
import pt.isel.Table

@Table("directors")
data class Director (
    @Pk
    val name: String,
    val age: Int
)