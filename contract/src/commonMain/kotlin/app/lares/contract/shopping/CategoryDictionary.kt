package app.lares.contract.shopping

import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ProductCategory.BABY
import app.lares.contract.entities.ProductCategory.BAKERY
import app.lares.contract.entities.ProductCategory.DAIRY
import app.lares.contract.entities.ProductCategory.DRINKS
import app.lares.contract.entities.ProductCategory.FROZEN
import app.lares.contract.entities.ProductCategory.GROCERY
import app.lares.contract.entities.ProductCategory.HOUSEHOLD
import app.lares.contract.entities.ProductCategory.MEAT_FISH
import app.lares.contract.entities.ProductCategory.PERSONAL_CARE
import app.lares.contract.entities.ProductCategory.PETS
import app.lares.contract.entities.ProductCategory.PHARMACY
import app.lares.contract.entities.ProductCategory.PRODUCE
import app.lares.contract.entities.ProductCategory.SWEETS

/**
 * The built-in department dictionary (architecture/shopping.md § Departments). Every entry is written as a
 * [ShoppingNames.key]: lower case, `е` for `ё`, letters only. A stem belongs to one category only; where a short stem
 * would catch another product, a longer stem in the right category wins (`печенье` over `печень`, `лимонад` over
 * `лимон`). Russian first, then English.
 */
internal object CategoryDictionary {
    val stems: Map<ProductCategory, List<String>> =
        mapOf(
            PRODUCE to
                words(
                    "яблок банан картош картоф помидор томат огурц огурец лук морков укроп лимон капуст свекл чеснок перец перц " +
                        "петрушк зелень салат апельсин мандарин груш виноград клубник авокадо кабачк баклажан редис гриб овощ " +
                        "фрукт ягод киви персик черри",
                    "apple banana potato tomato onion carrot cucumber lemon lime orange grape pear lettuce cabbage garlic pepper " +
                        "avocado spinach broccoli berr strawberr mushroom zucchini celery peach plum cherr melon watermelon dill " +
                        "parsley kiwi pea",
                ),
            BAKERY to
                words(
                    "хлеб хлебц батон лаваш круассан булк булочк багет сушк сухар пирог пирожк лепешк баранк бублик пончик " +
                        "ватрушк плюшк чиабатт тортиль слойк каравай",
                    "bread bagel bun baguette croissant muffin loaf pita tortilla brioche ciabatta pie donut doughnut scone " +
                        "pretzel crumpet toast sourdough",
                ),
            DAIRY to
                words(
                    "молок кефир сметан творог творож сыр сырок сырк масл яйц яиц йогурт ряженк сливк простокваш брынз " +
                        "моцарелл пармезан айран маргарин варенц",
                    "milk egg butter cheese cheddar yogurt yoghurt cream kefir mozzarella parmesan feta ricotta margarine " +
                        "buttermilk brie gouda",
                ),
            MEAT_FISH to
                words(
                    "куриц курин филе фарш колбас сосиск сардельк лосос креветк говядин свинин баранин индейк мяс ветчин бекон " +
                        "грудинк печень рыб семг форел треск минтай кальмар окороч крылышк котлет шашлык стейк тунец тунц " +
                        "селедк сельд скумбри буженин",
                    "chicken beef pork salmon tuna ham bacon sausage turkey lamb steak mince fish shrimp prawn cod sardine meat " +
                        "liver salami duck crab trout veal",
                ),
            GROCERY to
                words(
                    "греч рис макарон мук сахар сол круп овсянк хлопья майонез кетчуп горчиц уксус паст спагетт вермишел лапш " +
                        "пшен булгур кускус фасол чечевиц соус специ приправ тушенк мед варень джем сгущенк дрожж крахмал " +
                        "маслин оливк сод",
                    "rice pasta flour oil sugar salt oat oatmeal cereal noodle spaghetti bean lentil chickpea ketchup mayo " +
                        "mayonnaise mustard vinegar honey jam sauce spice yeast olive quinoa couscous buckwheat soup",
                ),
            SWEETS to
                words(
                    "печенье печенья шоколад конфет чипс пастил мармелад зефир вафл торт пирожн пряник халв батончик жвачк " +
                        "сухарик орех семечк попкорн крекер кекс леденц карамел драже щербет",
                    "cookie chocolate candy candies chip crisp gum cake biscuit marshmallow gummy popcorn nut cracker wafer " +
                        "lollipop toffee caramel brownie snack licorice",
                ),
            DRINKS to
                words(
                    "вод сок чай кофе пив лимонад газировк морс компот квас вин коньяк водк шампанск кол какао энергетик тоник " +
                        "нектар минералк кисел смузи коктейл напит",
                    "juice tea coffee water soda beer wine lemonade cola cocoa kombucha smoothie vodka whiskey whisky rum cider " +
                        "champagne tonic espresso latte",
                ),
            FROZEN to
                words(
                    "морожен мороженк пельмен вареник лед пицц наггетс блинчик хинкал мант чебурек эскимо пломбир сорбет фри " +
                        "голубц тефтел лазань полуфабрикат сырник",
                    "ice icecream popsicle dumpling pizza nugget gelato sorbet fries lasagna lasagne fishstick hashbrown " +
                        "pierogi sherbet tater",
                ),
            HOUSEHOLD to
                words(
                    "бумаг порош губк мешк мешок батарейк средств салфетк полотенц пакет фольг пленк отбеливател освежител " +
                        "лампочк свеч спичк тряпк швабр перчатк ополаскивател белизн скотч зажигалк мусор",
                    "paper detergent laundry bleach sponge battery batteri trash garbage foil napkin towel candle bulb " +
                        "dishwasher cleaner softener wipe glove mop broom tape glue lighter toilet pillow",
                ),
            PERSONAL_CARE to
                words(
                    "шампун дезодорант мыл щетк бальзам гель крем лосьон бритв станк помад тушь прокладк тампон зубочистк " +
                        "пенк скраб маск дух пудр лак расческ мочалк антиперспирант",
                    "toothpast toothbrush shampoo conditioner soap deodorant lotion razor floss tampon pad mouthwash sunscreen " +
                        "lipstick mascara perfume cologne moisturizer gel",
                ),
            BABY to
                words(
                    "подгузник памперс пюре соск бутылочк пустышк погремушк слюнявчик ползунк распашонк пеленк присыпк кашк " +
                        "нагрудник прорезывател молокоотсос поильник коляск игрушк трусик",
                    "diaper nappy nappi formula pacifier rattle onesie bib stroller teether sippy crib pram infant toddler",
                ),
            PETS to
                words(
                    "корм наполнител лоток лотк когтеточк ошейник поводок поводк миск лакомств кошач собач кошк кошек кот " +
                        "собак щенк котят попуга хомяк аквариум шлейк переноск",
                    "cat dog kitten puppy litter kibble catnip leash birdseed aquarium hamster parrot petfood flea pet budgie",
                ),
            PHARMACY to
                words(
                    "парацетамол пластыр таблетк ибупрофен аспирин нурофен витамин бинт йод зеленк капл сироп мазь градусник " +
                        "термометр презерватив антисептик перекис анальгин лекарств цитрамон валериан смект шприц ват",
                    "ibuprofen paracetamol aspirin acetaminophen vitamin bandage plaster antiseptic thermometer painkiller " +
                        "cough ointment tablet pill medicine antihistamine condom iodine probiotic melatonin lozenge",
                ),
        )

    /** Stems of consecutive words; the longest matching phrase decides before single words. */
    val phrases: Map<ProductCategory, List<String>> =
        mapOf(
            GROCERY to
                listOf(
                    "подсолнечн масл",
                    "оливков масл",
                    "растительн масл",
                    "томатн паст",
                    "olive oil",
                    "peanut butter",
                    "tomato past",
                    "baking soda",
                ),
            PERSONAL_CARE to listOf("зубн паст", "зубн нит", "ватн диск", "cotton pad"),
            HOUSEHOLD to listOf("мыть посуд", "dish soap", "toilet paper", "dishwasher tablet"),
            BABY to listOf("детск питан", "детск смес", "детск крем", "baby food", "baby wipe", "baby formula"),
            FROZEN to listOf("ice cream", "рыбн палочк"),
            PETS to listOf("cat food", "dog food"),
            MEAT_FISH to listOf("hot dog"),
        )

    /** Stems that decide the department wherever they stand (prefix match, any length after it). */
    val modifiers: Map<ProductCategory, List<String>> =
        mapOf(
            FROZEN to listOf("замороженн", "заморожен", "frozen"),
            GROCERY to listOf("консерв", "canned", "tinned"),
        )

    private fun words(vararg lines: String): List<String> = lines.flatMap { it.split(' ') }.filter { it.isNotEmpty() }
}
