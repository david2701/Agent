package app.kairo.domain

/**
 * Condición SQL de visibilidad: filas del usuario más las que alguien de su familia compartió.
 * Usa dos parámetros, en orden: `userId, userId`. [a] es el prefijo de la tabla (`"t."` para `from tasks t`).
 *
 * Lo personal sigue siendo privado: ni el administrador de la familia ve lo que no está compartido.
 */
internal fun visibleTo(a: String = ""): String =
    "(${a}user_id = ? or (${a}shared and ${a}household_id = $HOUSEHOLD_OF))"

/** Hogar de un usuario (un parámetro: `userId`). */
internal const val HOUSEHOLD_OF = "(select hh.household_id from users hh where hh.id = ?)"
