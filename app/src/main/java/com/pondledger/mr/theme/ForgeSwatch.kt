package com.pondledger.mr.theme

import androidx.compose.ui.graphics.Color

/** Display sRGB colors for the forge's printed panels, calibration marks and frenzy indicator. */
internal enum class ForgeSwatch(argb: Long) {
    MenuCharcoal(0xFF181F22),
    MenuDark(0xFF101517),
    MenuGold(0xFFFFCD83),
    MenuCream(0xFFFAF3E4),
    MenuMuted(0xFFCED4D0),
    MenuButton(0xFF3D484A),
    MenuCopper(0xFFBE8A55),

    WorkshopFace(0xFF1B292F),
    WorkshopDeep(0xFF111C20),
    WorkshopControl(0xFF3B5057),
    WorkshopSelected(0xFFB9DED3),
    WorkshopBronze(0xFFFFCB87),
    WorkshopText(0xFFFCF3E5),
    WorkshopSecondary(0xFFC9D8D6),

    LedgerParchment(0xFFF6EADA),
    LedgerCoolAsh(0xFFBBD0CD),
    LedgerHotBrass(0xFFFFC987),
    LedgerColdIron(0xFF35474B),
    LedgerDarkInk(0xFF2E241C),

    FrenzyRose(0xFFFF468F),
    FrenzyAmber(0xFFFFAF29),
    FrenzyLime(0xFFCDFF30),
    FrenzyMint(0xFF20F2A4),
    FrenzyIce(0xFF23E3FF),
    FrenzyBlue(0xFF8296FF),
    FrenzyLilac(0xFFEA61FF),
    FrenzyInk(0xFF12101D),
    FrenzyRail(0xFF43374D),
    FrenzyPaper(0xFFFFF3EB),
    FrenzyDeadline(0xFFFF914D),

    EyeTracked(0xFF83F4DC),
    EyeWaiting(0xFFDFD9C8),
    EyeInstruction(0xFFF5DEAC),
    EyeTrack(0xFF111D20),
    EyeInactive(0xFF6B797B),

    RoomRecallBackdrop(0xEB172426);

    val color = Color(argb)
}
