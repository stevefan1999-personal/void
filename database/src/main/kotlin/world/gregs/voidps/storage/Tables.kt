package world.gregs.voidps.storage

import org.jetbrains.exposed.sql.Table

internal object AccountsTable : Table("accounts") {
    // Do not add `.uniqueIndex()` – the column is already the primary key, and
    // Oracle rejects the duplicate UNIQUE constraint with ORA-02261.
    val id = integer("id").autoIncrement()
    val name = varchar("name", 12).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val tile = integer("tile")
    val blockedSkills = intList("blocked")
    val male = bool("male")
    val looks = intList("looks")
    val colours = intList("colours")
    val friends = stringList("friends")
    val ranks = stringList("ranks")
    val ignores = stringList("ignores")

    override val primaryKey = PrimaryKey(id, name = "pk_account_id")
}

internal object ExperienceTable : Table("experience") {
    val playerId = integer("player_id").references(AccountsTable.id).uniqueIndex()
    val attack = integer("attack").default(0)
    val defence = integer("defence").default(0)
    val strength = integer("strength").default(0)
    val constitution = integer("constitution").default(0)
    val ranged = integer("ranged").default(0)
    val prayer = integer("prayer").default(0)
    val magic = integer("magic").default(0)
    val cooking = integer("cooking").default(0)
    val woodcutting = integer("woodcutting").default(0)
    val fletching = integer("fletching").default(0)
    val fishing = integer("fishing").default(0)
    val firemaking = integer("firemaking").default(0)
    val crafting = integer("crafting").default(0)
    val smithing = integer("smithing").default(0)
    val mining = integer("mining").default(0)
    val herblore = integer("herblore").default(0)
    val agility = integer("agility").default(0)
    val thieving = integer("thieving").default(0)
    val slayer = integer("slayer").default(0)
    val farming = integer("farming").default(0)
    val runecrafting = integer("runecrafting").default(0)
    val hunter = integer("hunter").default(0)
    val construction = integer("construction").default(0)
    val summoning = integer("summoning").default(0)
    val dungeoneering = integer("dungeoneering").default(0)
}

internal object LevelsTable : Table("levels") {
    val playerId = integer("player_id").references(AccountsTable.id).uniqueIndex()
    val attack = integer("attack").default(1)
    val defence = integer("defence").default(1)
    val strength = integer("strength").default(1)
    val constitution = integer("constitution").default(1)
    val ranged = integer("ranged").default(1)
    val prayer = integer("prayer").default(1)
    val magic = integer("magic").default(1)
    val cooking = integer("cooking").default(1)
    val woodcutting = integer("woodcutting").default(1)
    val fletching = integer("fletching").default(1)
    val fishing = integer("fishing").default(1)
    val firemaking = integer("firemaking").default(1)
    val crafting = integer("crafting").default(1)
    val smithing = integer("smithing").default(1)
    val mining = integer("mining").default(1)
    val herblore = integer("herblore").default(1)
    val agility = integer("agility").default(1)
    val thieving = integer("thieving").default(1)
    val slayer = integer("slayer").default(1)
    val farming = integer("farming").default(1)
    val runecrafting = integer("runecrafting").default(1)
    val hunter = integer("hunter").default(1)
    val construction = integer("construction").default(1)
    val summoning = integer("summoning").default(1)
    val dungeoneering = integer("dungeoneering").default(1)
}

internal object VariablesTable : Table("variables") {
    val playerId = integer("player_id").references(AccountsTable.id)
    val name = varchar("name", 128)
    val type = byte("type")
    val string = text("string_value").nullable().default(null)
    val int = integer("int_value").nullable().default(null)
    val boolean = bool("boolean_value").nullable().default(null)
    val double = double("double_value").nullable().default(null)
    val long = long("long_value").nullable().default(null)
    val stringList = stringList("string_list_value").nullable().default(null)
    val intList = intList("int_list_value").nullable().default(null)

    init {
        index(true, playerId, name)
    }
}

internal object InventoriesTable : Table("inventories") {
    val playerId = integer("player_id").references(AccountsTable.id)
    val inventoryName = varchar("inventory_name", 64)
    val items = stringList("items")
    val amounts = intList("amounts")

    init {
        index(true, playerId, inventoryName)
    }
}

internal object OffersTable : Table("grand_exchange_offers") {
    val playerId = integer("player_id").references(AccountsTable.id)
    val id = integer("id")
    // SQL column is `slot`, not `index`, because `INDEX` is a reserved word in
    // Oracle and Exposed 0.48 does not auto-quote it.
    val index = integer("slot")
    val item = varchar("item", 128)
    val amount = integer("amount")
    val price = integer("price")
    val state = varchar("state", 32)
    val completed = integer("completed").default(0)
    val lastActive = long("last_active").default(0)
    val coins = integer("coins").default(0)

    init {
        index(true, playerId, index, id)
    }

    override val primaryKey = PrimaryKey(id, name = "pk_offer_id")
}

internal object ActiveOffersTable : Table("grand_exchange_active_offers") {
    val playerId = integer("player_id").references(AccountsTable.id)
    val id = integer("id")
    val item = varchar("item", 128)
    val remaining = integer("remaining")
    val price = integer("price")
    val coins = integer("coins").default(0)
    val lastActive = long("last_active").default(0)
    val sell = bool("sell").default(true)

    init {
        index(true, playerId, id)
    }

    override val primaryKey = PrimaryKey(OffersTable.id, name = "pk_active_offer_id")
}

internal object PlayerHistoryTable : Table("player_exchange_history") {
    val playerId = integer("player_id").references(AccountsTable.id)
    // See `OffersTable.index` – `INDEX` is reserved on Oracle.
    val index = integer("slot")
    val item = varchar("item", 128)
    val amount = integer("amount")
    val coins = integer("coins")
    init {
        index(true, playerId, index)
    }
}

internal object ClaimsTable : Table("grand_exchange_claims") {
    val offerId = integer("offer_id").references(OffersTable.id).uniqueIndex()
    val amount = integer("amount")
    val coins = integer("coins")
}

internal object ItemHistoryTable : Table("grand_exchange_item_history") {
    val item = varchar("item", 128)
    val timestamp = long("timestamp")
    val timeframe = varchar("timeframe", 16)
    val open = integer("open")
    val high = integer("high")
    val low = integer("low")
    val close = integer("close")
    val volume = long("volume")
    val count = integer("count")
    val averageHigh = double("average_high")
    val averageLow = double("average_low")
    val volumeHigh = long("volume_high")
    val volumeLow = long("volume_low")
}
