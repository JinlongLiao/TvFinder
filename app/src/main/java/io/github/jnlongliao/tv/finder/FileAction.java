package io.github.jnlongliao.tv.finder;

/** 文件动作使用稳定枚举分派，禁止按翻译后的按钮文字决定复制、移动或删除行为。 */
public enum FileAction {
    /** 复制源到目标目录，源文件保持不变。 */
    COPY(R.string.action_copy),
    /** 完整复制与校验后删除源，源删除阶段不可回滚。 */
    MOVE(R.string.action_move),
    /** 永久删除用户已确认的文件树。 */
    DELETE(R.string.action_delete),
    /** 在相同父目录中修改名称。 */
    RENAME(R.string.action_rename),
    /** 在当前目录创建一个空文件夹。 */
    CREATE_DIRECTORY(R.string.action_new_folder);

    /** 对应当前界面语言的动作名称资源；业务分派不依赖其内容。 */
    public final int labelResource;

    /**
     * 绑定不可变的展示名称资源。
     * @param labelResource Android 字符串资源 ID
     */
    FileAction(int labelResource) {
        this.labelResource = labelResource;
    }
}
