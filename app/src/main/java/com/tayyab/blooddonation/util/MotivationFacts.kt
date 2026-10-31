package com.tayyab.blooddonation.util

/**
 * The daily motivation card's text, and the tips used by the optional
 * notification.
 *
 * Rules these strings follow:
 * - No medical claims. Nothing here says a donation cures, prevents or treats
 *   anything, and nothing promises weight loss or any personal benefit.
 * - No invented statistics. Figures are the standard ones a donation centre
 *   would give you (unit volume, shelf life, replacement times, the 90-day gap).
 * - Short enough to read on a card.
 *
 * The list is ordered so consecutive days do not repeat a theme.
 */
object MotivationFacts {

    /** One entry per day, cycling. Kept at 30+ so a month does not repeat. */
    val FACTS: List<String> = listOf(
        "One donation can be separated into red cells, plasma and platelets, so up to three patients may benefit.",
        "Blood cannot be manufactured. Every unit in a hospital came from a person who chose to give it.",
        "A whole blood donation is about 450 ml, roughly a tenth of the blood an adult carries.",
        "Your body replaces the plasma from a donation within about a day.",
        "Red cells are replaced over the following weeks, which is why there is a waiting period between donations.",
        "Most healthy adults can give whole blood again after about 90 days. This app uses that rule.",
        "Platelets have a shelf life of only about five to seven days, so the supply has to keep moving.",
        "Donated red cells are usually used within about five to six weeks.",
        "O negative red cells can be given to any patient, which is why O negative donors are always in demand.",
        "AB positive plasma can be given to any patient.",
        "Your blood group is inherited and does not change during your life.",
        "Rh negative donors are a small share of most populations, so their blood is especially valuable.",
        "Hospitals use blood for surgery, childbirth, cancer treatment and serious injury.",
        "A new sterile needle is used for every donor. You cannot catch an infection by giving blood.",
        "Drink water before you donate. It helps keep your blood pressure steady.",
        "Eat something beforehand so your blood sugar is not low when you arrive.",
        "The needle stays in for roughly eight to twelve minutes for a whole blood donation.",
        "Most of an appointment is screening and rest. The donation itself is the short part.",
        "Take it easy for the rest of the day. Skip heavy lifting and hard exercise.",
        "If you feel light-headed afterwards, sit or lie down and raise your legs.",
        "A drink and a snack after donating is all most people need.",
        "Donated blood is tested for blood group and for several infections before it is used.",
        "Donation centres screen every donor first. Their decision on eligibility always comes before this app's.",
        "If you are deferred today, it is usually temporary. Ask the centre when you can come back.",
        "In Pakistan, blood donation is voluntary and unpaid.",
        "Regular donors help hospitals plan ahead, because the supply is steadier.",
        "Iron levels matter for frequent donors. Ask the centre about checking yours.",
        "Checking your eligibility in the app before travelling saves a wasted trip.",
        "Telling one friend about donation is another way to help.",
        "Someone in your city may need blood today. Requests in this app show who.",
        "A short appointment can still be the reason a family gets good news.",
        "Every unit is used by a person with a name, not by a number."
    )

    /**
     * Practical tips for the optional notification. Same rules as [FACTS]: no
     * medical claims, no promises about the donor's own health.
     */
    val TIPS: List<String> = listOf(
        "Drink a glass of water before you set off to donate.",
        "Have a proper meal within a couple of hours of donating.",
        "Bring your ID and a list of any medicines you take.",
        "Wear a sleeve that can be rolled up easily.",
        "Tell the centre if you have been unwell in the last few weeks.",
        "Plan a quiet evening after a donation rather than the gym.",
        "Keep your iron intake up if you donate regularly.",
        "Check your next eligible date in this app before you travel.",
        "If you felt faint last time, tell the staff before you donate again.",
        "A friend who is eligible might donate alongside you.",
        "Requests in this app show the city and blood group. Check if any match you.",
        "Your last donation date is on your profile. It decides your next eligible date."
    )

    /** The fact for a given day, stable for the whole day and the same on every device. */
    fun factFor(epochDay: Long): String = pick(FACTS, epochDay)

    /** The tip for a given day, same rotation rule as [factFor]. */
    fun tipFor(epochDay: Long): String = pick(TIPS, epochDay)

    private fun pick(source: List<String>, epochDay: Long): String {
        // Floor-mod, so a negative epoch day (dates before 1970) cannot index
        // outside the list.
        val index = ((epochDay % source.size) + source.size) % source.size
        return source[index.toInt()]
    }
}
