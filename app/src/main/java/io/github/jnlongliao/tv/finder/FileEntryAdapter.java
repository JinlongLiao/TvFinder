package io.github.jnlongliao.tv.finder;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.AbsListView;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * 文件网格复用卡片视图，保持长目录的遥控器滚动流畅；数据仅在 UI 线程访问。
 */
public final class FileEntryAdapter extends BaseAdapter {
    /**
     * 当前页面上下文，与列表生命周期一致。
     */
    private final Context context;
    /**
     * 已排序的本次目录快照，构造后不变更。
     */
    private final List<File> files;

    /**
     * 绑定当前目录快照。
     *
     * @param context 页面上下文
     * @param files   文件夹优先排列的文件列表
     */
    public FileEntryAdapter(Context context, List<File> files) {
        this.context = context;
        this.files = files;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getCount() {
        return files.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public File getItem(int position) {
        return files.get(position);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getItemId(int position) {
        return position;
    }

    /**
     * {@inheritDoc} 行本身不抢夺焦点，由 GridView 的选择器绘制遥控器焦点。
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout row;
        if (Objects.isNull(convertView)) {
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER);
            int densityHeight = Math.round(128 * context.getResources().getDisplayMetrics().density);
            row.setLayoutParams(new AbsListView.LayoutParams(-1, densityHeight));
            row.setPadding(12, 10, 12, 10);
            ImageView icon = new ImageView(context);
            int iconSize = Math.round(44 * context.getResources().getDisplayMetrics().density);
            row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView title = new TextView(context);
            title.setTextSize(18);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, 8, 0, 4);
            title.setTextColor(context.getColor(R.color.text_primary));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(title);
            TextView detail = new TextView(context);
            detail.setTextSize(13);
            detail.setSingleLine(true);
            detail.setGravity(Gravity.CENTER);
            detail.setTextColor(context.getColor(R.color.text_secondary));
            row.addView(detail);
        } else {
            row = (LinearLayout) convertView;
        }
        File file = getItem(position);
        FileCategory category = FileTypeResolver.resolveFileCategory(file.getName(), file.isDirectory());
        ((ImageView) row.getChildAt(0)).setImageResource(category.iconResource);
        ((TextView) row.getChildAt(1)).setText(file.getName());
        String information = file.isDirectory() ? context.getString(R.string.folder_type)
            : context.getString(R.string.file_type_size, context.getString(category.labelResource),
            Formatter.formatFileSize(context, file.length()));
        ((TextView) row.getChildAt(2)).setText(information);
        return row;
    }
}
