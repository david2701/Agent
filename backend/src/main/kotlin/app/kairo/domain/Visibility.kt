package app.kairo.domain

/**
 * SQL visibility condition: the user's rows plus the ones someone in their family shared.
 * It uses two parameters, in order: `userId, userId`. [a] is the table prefix (`"t."` for `from tasks t`).
 *
 * Personal items stay private: not even the family administrator sees what is not shared.
 */
internal fun visibleTo(a: String = ""): String =
    "(${a}user_id = ? or (${a}shared and ${a}household_id = $HOUSEHOLD_OF))"

/** A user's household (one parameter: `userId`). */
internal const val HOUSEHOLD_OF = "(select hh.household_id from users hh where hh.id = ?)"
