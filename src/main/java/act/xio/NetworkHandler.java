package act.xio;

import act.Act;
import act.app.ActionContext;
import act.app.App;
import act.app.util.NamedPort;
import act.handler.RequestHandler;
import act.handler.builtin.controller.FastRequestHandler;
import act.handler.builtin.controller.RequestHandlerProxy;
import act.metric.Metric;
import act.metric.MetricInfo;
import act.metric.Timer;
import act.route.Router;
import act.util.DestroyableBase;
import act.view.ActErrorResult;
import org.osgl.$;
import org.osgl.Osgl;
import org.osgl.exception.NotAppliedException;
import org.osgl.http.H;
import org.osgl.logging.LogManager;
import org.osgl.logging.Logger;
import org.osgl.mvc.result.ErrorResult;
import org.osgl.mvc.result.Result;
import org.osgl.util.E;
import org.osgl.util.S;

/**
 * A `NetworkHandler` can be registered to an {@link Network} and get invoked when
 * there are network event (e.g. an HTTP request) incoming
 */
public class NetworkHandler extends DestroyableBase {

    private static Logger logger = LogManager.get(NetworkHandler.class);

    final private App app;
    private NamedPort port;
    private Metric metric;
    private $.Func2<H.Request, String, String> contentSuffixProcessor;

    public NetworkHandler(App app) {
        E.NPE(app);
        this.app = app;
        this.metric = Act.metricPlugin().metric("act.http");
        this.contentSuffixProcessor = app.config().contentSuffixAware() ? new ContentSuffixSensor() : DUMB_CONTENT_SUFFIX_SENSOR;
    }

    public NetworkHandler(App app, NamedPort port) {
        this(app);
        this.port = port;
    }

    public App app() {
        return app;
    }

    public void handle(final ActionContext ctx, NetworkDispatcher dispatcher) {
        if (isDestroyed()) {
            return;
        }
        final H.Request req = ctx.req();
        String url = req.url();
        H.Method method = req.method();
        if (Act.isDev() && !url.startsWith("/asset/")) {
            app.checkUpdates(false);
        }
        url = contentSuffixProcessor.apply(req, url);
        Timer timer = metric.startTimer(MetricInfo.ROUTING);
        final RequestHandler requestHandler = router().getInvoker(method, url, ctx);
        ctx.handler(requestHandler);
        timer.stop();
        NetworkJob job = new NetworkJob() {
            @Override
            public void run() {
                Timer timer = metric.startTimer(S.builder(MetricInfo.HTTP_HANDLER).append(":").append(requestHandler).toString());
                ctx.saveLocal();
                try {
                    requestHandler.handle(ctx);
                } catch (Result r) {
                    try {
                        r = RequestHandlerProxy.GLOBAL_AFTER_INTERCEPTOR.apply(r, ctx);
                    } catch (Exception e) {
                        logger.error(e, "Error calling global after interceptor");
                        r = ActErrorResult.of(e);
                    }
                    if (null == ctx.handler()) {
                        ctx.handler(FastRequestHandler.DUMB);
                    }

                    H.Format fmt = req.accept();
                    if (H.Format.UNKNOWN == fmt) {
                        fmt = req.contentType();
                    }

                    ctx.resp().addHeaderIfNotAdded(H.Header.Names.CONTENT_TYPE, fmt.contentType());
                    r.apply(req, ctx.resp());
                } catch (Exception t) {
                    logger.error(t, "Error handling network request");
                    Result r;
                    try {
                        r = RequestHandlerProxy.GLOBAL_EXCEPTION_INTERCEPTOR.apply(t, ctx);
                    } catch (Exception e) {
                        logger.error(e, "Error calling global exception interceptor");
                        r = ActErrorResult.of(e);
                    }
                    if (null == r) {
                        r = ActErrorResult.of(t);
                    } else if (r instanceof ErrorResult) {
                        r = ActErrorResult.of(r);
                    }
                    if (null == ctx.handler()) {
                        ctx.handler(FastRequestHandler.DUMB);
                    }
                    r.apply(req, ctx.resp());
                } finally {
                    // we don't destroy ctx here in case it's been passed to
                    // another thread
                    ActionContext.clearCurrent();
                    if (null != timer) {
                        timer.stop();
                    }
                }
            }
        };
        if (method.unsafe() || !requestHandler.express(ctx)) {
            dispatcher.dispatch(job);
        } else {
            job.run();
        }
    }

    @Override
    public String toString() {
        return app().name();
    }

    private Router router() {
        return app.router(port);
    }

    private static $.Func2<H.Request, String, String> DUMB_CONTENT_SUFFIX_SENSOR = new $.Func2<H.Request, String, String>() {
        @Override
        public String apply(H.Request request, String s) throws NotAppliedException, Osgl.Break {
            return s;
        }
    };

    /**
     * Process URL suffix based on suffix
     */
    private static class ContentSuffixSensor implements $.Func2<H.Request, String, String> {

        private static final char[] json = {'j', 's', 'o'};
        private static final char[] xml = {'x', 'm'};
        private static final char[] csv = {'c', 's'};
        private static final char[] xls = {'x', 'l', 's'};
        private static final char[] xlsx = {'x', 'l', 's', 'x'};
        private static final char[] pdf = {'p', 'd', 'f'};

        @Override
        public String apply(H.Request req, String url) throws NotAppliedException, Osgl.Break {
            int sz = url.length();
            if (sz < 4) {
                return url;
            }
            int start = sz - 1;
            char c = url.charAt(start);
            char[] trait;
            int sepPos = 3;
            H.Format fmt = H.Format.JSON;
            switch (c) {
                case 'n':
                    sepPos = 4;
                    trait = json;
                    break;
                case 'l':
                    trait = xml;
                    fmt = H.Format.XML;
                    break;
                case 'v':
                    trait = csv;
                    fmt = H.Format.CSV;
                    break;
                case 'f':
                    trait = pdf;
                    fmt = H.Format.PDF;
                    break;
                case 's':
                    trait = xls;
                    fmt = H.Format.XLS;
                    break;
                case 'x':
                    sepPos = 4;
                    trait = xlsx;
                    fmt = H.Format.XLSX;
                    break;
                default:
                    return url;
            }
            char sep = url.charAt(start - sepPos);
            if (sep != '.' && sep != '/') {
                return url;
            }
            for (int i = 1; i < sepPos; ++i) {
                if (url.charAt(start - i) != trait[sepPos - i - 1]) {
                    return url;
                }
            }
            req.accept(fmt);
            return url.substring(0, sz - sepPos - 1);
        }
    }
}
