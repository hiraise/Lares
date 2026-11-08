package app.lares.contract.budget

import app.lares.contract.entities.BudgetCategory
import app.lares.contract.entities.BudgetCell
import app.lares.contract.entities.BudgetIncome
import app.lares.contract.entities.BudgetTable

/**
 * The customer's notebook (budget spec § Scenarios): 346 000 a month in four incomes, every row and column adds up.
 * Plus one unused category with a leftover cell, which every sum must ignore.
 */
object Notebook {
    fun id(n: Int): String = "0192f3a4-0000-7000-8000-" + n.toString().padStart(12, '0')

    val i5 = id(105)
    val i10 = id(110)
    val i20 = id(120)
    val i25 = id(125)

    val food = id(1)
    val car = id(2)
    val utilities = id(3)
    val mortgage = id(4)
    val safe = id(5)
    val vacation = id(6)
    val gifts = id(7)
    val savings = id(8)
    val old = id(9)

    val table =
        BudgetTable(
            categories =
                listOf(
                    BudgetCategory(food, "Продукты", 60_000),
                    BudgetCategory(car, "Машина", 20_000),
                    BudgetCategory(utilities, "Коммуналка", 25_000),
                    BudgetCategory(mortgage, "Ипотека", 100_000),
                    BudgetCategory(safe, "Сейф", 20_000),
                    BudgetCategory(vacation, "Отпуск", 30_000),
                    BudgetCategory(gifts, "Подарки", 10_000),
                    BudgetCategory(savings, "Накопления", 81_000),
                    BudgetCategory(old, "Кружок", 5_000, unused = true),
                ),
            // Not in day order: the summary sorts by date.
            incomes =
                listOf(
                    BudgetIncome(i5, 5, 162_000),
                    BudgetIncome(i20, 20, 160_000),
                    BudgetIncome(i10, 10, 12_000),
                    BudgetIncome(i25, 25, 12_000),
                ),
            cells =
                listOf(
                    BudgetCell(food, i5, 27_000),
                    BudgetCell(food, i10, 2_000),
                    BudgetCell(food, i20, 29_000),
                    BudgetCell(food, i25, 2_000),
                    BudgetCell(car, i5, 10_000),
                    BudgetCell(car, i20, 10_000),
                    BudgetCell(utilities, i5, 25_000),
                    BudgetCell(mortgage, i5, 100_000),
                    BudgetCell(safe, i10, 10_000),
                    BudgetCell(safe, i25, 10_000),
                    BudgetCell(vacation, i20, 30_000),
                    BudgetCell(gifts, i20, 10_000),
                    BudgetCell(savings, i20, 81_000),
                    BudgetCell(old, i5, 5_000),
                ),
        )
}
