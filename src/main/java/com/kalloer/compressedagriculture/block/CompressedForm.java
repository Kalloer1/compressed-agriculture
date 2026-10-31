package com.kalloer.compressedagriculture.block;

/**
 * 压缩方块的三种形态。
 *
 * <p>形态由方块类本身表达，与等级正交，都不存进 BlockEntity（GLOSSARY「形态」「等级」）。形态互转由 ticket 09 负责，
 * 转换不损失货仓内容（ADR-0002）。
 */
public enum CompressedForm {
    /** 压缩泥土。 */
    DIRT,
    /** 压缩草地。 */
    GRASS,
    /** 压缩耕地。额外携带 {@code MOISTURE} 属性（ADR-0018）。 */
    FARMLAND
}
