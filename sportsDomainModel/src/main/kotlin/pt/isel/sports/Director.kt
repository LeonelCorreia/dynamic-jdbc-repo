package pt.isel.sports

import pt.isel.Pk
import pt.isel.Table
import java.sql.Date

@Table("directors")
data class Director(
    @Pk
    val id: Int,
    val name: String,
    val birthdate: Date,
)
