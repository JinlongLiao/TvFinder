package io.github.jnlongliao.tv.finder;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;

/**
 * 将 HTML 同目录资源限制在正在预览文件的目录；其他域名交给 WebView 正常加载。
 * JS 可以运行，但不能通过应用伪造的 tvfinder.local 地址读取上级目录或任意电视文件。
 *
 * @see <a href="https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,%20android.webkit.WebResourceRequest)">Android WebViewClient</a>
 */
public final class PreviewWebViewClient extends WebViewClient {
    /** WebView 使用的内部虚拟域名。 */
    private static final String PREVIEW_HOST = "tvfinder.local";
    /** 同目录资源的 URL 前缀。 */
    private static final String PREVIEW_PREFIX = "/preview/";
    /** 应用上下文，不持有页面生命周期。 */
    private final Context context;
    /** 主 HTML 文件的只读 URI。 */
    private final Uri mainUri;
    /** 本地文件的已校验父目录；USB 使用卷内路径。 */
    private final String parentPath;
    /** 是否来自 USB FAT/exFAT。 */
    private final boolean usbSource;

    /**
     * 固定 HTML 文件的资源访问边界。
     * @param context 当前预览上下文
     * @param mainUri 主文件 URI
     * @param parentPath 本地父目录，USB 时可为空
     * @param usbSource 是否为 USB 文件
     */
    public PreviewWebViewClient(Context context, Uri mainUri, String parentPath, boolean usbSource) {
        this.context = context.getApplicationContext();
        this.mainUri = mainUri;
        this.parentPath = parentPath;
        this.usbSource = usbSource;
    }

    /**
     * {@inheritDoc} 仅代理内部虚拟域名，同目录之外的请求返回空资源。
     */
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (!PREVIEW_HOST.equals(uri.getHost())) {
            return null;
        }
        String path = uri.getPath();
        if (Objects.isNull(path) || !path.startsWith(PREVIEW_PREFIX)) {
            return unavailableResource();
        }
        String relative = path.substring(PREVIEW_PREFIX.length());
        if (relative.isEmpty()) {
            return unavailableResource();
        }
        try {
            InputStream input;
            if (usbSource) {
                Uri resourceUri = UsbPreviewProvider.registerAdjacentUsbFile(context, mainUri, relative);
                input = context.getContentResolver().openInputStream(resourceUri);
            } else {
                if (Objects.isNull(parentPath)) {
                    return unavailableResource();
                }
                File parent = new File(parentPath).getCanonicalFile();
                File resource = new File(parent, relative).getCanonicalFile();
                if (!resource.getPath().startsWith(parent.getPath() + File.separator)
                    || !resource.isFile()) {
                    return unavailableResource();
                }
                input = new FileInputStream(resource);
            }
            if (Objects.isNull(input)) {
                return unavailableResource();
            }
            int dot = relative.lastIndexOf('.');
            String mime = dot < 0 ? null : MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(relative.substring(dot + 1).toLowerCase(Locale.ROOT));
            return new WebResourceResponse(Objects.isNull(mime) ? "application/octet-stream" : mime,
                "UTF-8", input);
        } catch (IOException | SecurityException exception) {
            Log.e("TvFinderPreview", "HTML 资源读取失败 uri=" + uri + ", stage=resource, type="
                + exception.getClass().getName() + ", message=" + exception.getMessage(), exception);
            return unavailableResource();
        }
    }

    /** 不向 WebView 暴露越界资源。 */
    private WebResourceResponse unavailableResource() {
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }
}
