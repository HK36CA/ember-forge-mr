package com.pondledger.mr

/** The image contains the albedo. Metallic and roughness are scalar PBR inputs. */
internal enum class ForgeFinish(val asset:String, val metallic:Float, val roughness:Float) {
    OAK("oak",.01f,.78f),
    HICKORY("hickory",.01f,.64f),
    BRASS("brass",.72f,.39f),
    COPPER("copper",.76f,.44f),
    STEEL("ground_steel",.80f,.32f),
    BLUED("blued_iron",.68f,.40f),
    IRON("hammered_iron",.65f,.57f),
    LEATHER("leather",.01f,.70f),
    FELT("oxblood_felt",0f,.95f),
    FIREBRICK("firebrick",0f,.93f),
    ENAMEL("teal_enamel",.08f,.25f),
    MAGNETITE("magnetite",.28f,.63f),
    STARSILVER("starsilver",.35f,.55f),
    DRAGONCRYSTAL("dragoncrystal",.18f,.38f)
}
