package pt.isel.sports

import pt.isel.Column
import pt.isel.Pk
import pt.isel.Table

@Table("clubs")
data class Club (
    @Pk
    val name : String,
    @Column("year")
    val foundedYear: Int,
    val director : Director
)