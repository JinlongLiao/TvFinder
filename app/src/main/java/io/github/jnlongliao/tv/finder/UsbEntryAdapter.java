package io.github.jnlongliao.tv.finder;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Objects;

/**
 * USB 直连目录的四列卡片适配器，使用与普通存储页相同的图标、尺寸和焦点层级。
 * 列表快照只在 UI 线程读取，真实文件 I/O 始终由 USB 工作线程完成。
 */
public final class UsbEntryAdapter extends BaseAdapter {
    /** 当前活动上下文。 */
    private final Context context;
    /** 文件系统返回的直接子项快照。 */
    private final List<FatFsVolume.DirectoryEntry> entries;

    /**
     * 绑定本次目录列表。
     * @param context 用于读取当前主题颜色和文件类型图标
     * @param entries 目录优先排序后的文件快照
     */
    public UsbEntryAdapter(Context context, List<FatFsVolume.DirectoryEntry> entries) {
        this.context = context;
        this.entries = entries;
    }

    /** {@inheritDoc} */
    @Override
    public int getCount() {
        return entries.size();
    }

    /** {@inheritDoc} */
    @Override
    public FatFsVolume.DirectoryEntry getItem(int position) {
        return entries.get(position);
    }

    /** {@inheritDoc} */
    @Override
    public long getItemId(int position) {
        return position;
    }

    /** {@inheritDoc} 卡片本身不抢焦点，由 GridView 统一绘制方向键选中状态。 */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout card;
        if (Objects.isNull(convertView)) {
            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            int height = Math.round(128 * context.getResources().getDisplayMetrics().density);
            card.setLayoutParams(new AbsListView.LayoutParams(-1, height));
            card.setPadding(12, 10, 12, 10);
            ImageView icon = new ImageView(context);
            int iconSize = Math.round(44 * context.getResources().getDisplayMetrics().density);
            card.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView name = new TextView(context);
            name.setTextSize(18);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, 8, 0, 4);
            name.setTextColor(context.getColor(R.color.text_primary));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            card.addView(name);
            TextView kind = new TextView(context);
            kind.setTextSize(13);
            kind.setSingleLine(true);
            kind.setGravity(Gravity.CENTER);
            kind.setTextColor(context.getColor(R.color.text_secondary));
            card.addView(kind);
        } else {
            card = (LinearLayout) convertView;
        }
        FatFsVolume.DirectoryEntry entry = getItem(position);
        FileCategory category = FileTypeResolver.resolveFileCategory(entry.name, entry.directory);
        ((ImageView) card.getChildAt(0)).setImageResource(category.iconResource);
        ((TextView) card.getChildAt(1)).setText(entry.name);
        ((TextView) card.getChildAt(2)).setText(entry.directory ? context.getString(R.string.folder_type)
            : context.getString(category.labelResource));
        return card;
    }
}
