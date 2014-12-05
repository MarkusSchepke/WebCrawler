/**
 * 
 */
package info.riemannhypothesis.web.crawler;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

/**
 * @author Markus Schepke
 * @date 4 Dec 2014
 */
public class Crawler {

    private final BloomFilter<String> visited;
    private final BlockingQueue<Job>  queue;
    private final int                 maxTries;
    private final int                 maxPages;
    private final WorkerThread[]      workers;
    private final int                 numWorkers;
    private final AtomicInteger       total;

    // private static final Set<URL> EMPTY_SET = new HashSet<URL>();

    public Crawler(int maxPages) {
        this(maxPages, 5, Runtime.getRuntime().availableProcessors() * 2,
                System.out);
    }

    public Crawler(int maxPages, int maxTries, PrintStream ps) {
        this(maxPages, maxTries,
                Runtime.getRuntime().availableProcessors() * 2, ps);
    }

    public Crawler(int maxPages, int maxTries, int numWorkers, PrintStream ps) {
        queue = new LinkedBlockingQueue<Job>();
        visited = BloomFilter.create(
                Funnels.stringFunnel(Charset.forName("UTF-8")), maxPages);
        this.maxTries = maxTries;
        this.maxPages = maxPages;
        total = new AtomicInteger(0);

        this.numWorkers = numWorkers;
        workers = new WorkerThread[numWorkers];

        for (int i = 0; i < numWorkers; i++) {
            workers[i] = new WorkerThread(ps);
        }
    }

    public void crawl(final URL url) {
        if (!visited.mightContain(url.toString())) {
            queue.offer(new Job(url));
        }
    }

    public static void main(String[] args) throws IOException {

        if (args.length < 2) {
            System.out.println("Usage: Crawler maxPages maxTries [-|outFile]");
        }

        int maxPages = Integer.parseInt(args[0], 10);
        int maxTries = Integer.parseInt(args[1], 10);
        PrintStream ps = args.length < 3 || "-".equals(args[2].trim()) ? System.out
                : new PrintStream(new File(args[2]));

        Crawler crawler = new Crawler(maxPages, maxTries, ps);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String s;
        while ((s = in.readLine()) != null) {
            try {
                crawler.crawl(new URL(s.trim()));
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }

        for (int i = 0; i < crawler.numWorkers; i++) {
            try {
                crawler.workers[i].join();
            } catch (InterruptedException e) {
            }
        }

    }

    public static Set<URL> getLinks(URL url) throws IOException {
        Document doc = Jsoup.connect(url.toString()).get();

        Set<URL> result = new HashSet<URL>();
        Elements links = doc.select("a[href]");

        for (Element link : links) {
            String linkURL = link.attr("abs:href");
            URL temp;
            try {
                temp = canonicalURL(new URL(linkURL));
            } catch (MalformedURLException e) {
                continue;
            }
            result.add(temp);
        }

        return result;
    }

    public static URL canonicalURL(final URL url) throws MalformedURLException {
        final String protocol = url.getProtocol().toLowerCase();
        final String file = url.getFile();
        final int port = url.getPort();
        final String host = url.getHost().toLowerCase();
        final URL result = new URL(protocol, host, port, file);
        assert result.sameFile(url);
        return result;
    }

    private class Job {
        private int       tries = 0;
        private final URL url;

        private Job(URL url) {
            this.url = url;
        }
    }

    private class WorkerThread extends Thread {
        private final PrintStream ps;

        private WorkerThread(final PrintStream ps) {
            this.ps = ps;
            setPriority(getPriority() - 1);
            start();
        }

        @Override
        public void run() {
            while (total.get() < maxPages) {
                final Job job;
                try {
                    job = queue.take();
                } catch (InterruptedException e) {
                    continue;
                }

                final String urlString = job.url.toString();
                if (visited.mightContain(urlString)) {
                    continue;
                }

                final Set<URL> links;
                try {
                    links = getLinks(job.url);
                } catch (IOException e) {
                    job.tries++;
                    if (job.tries < maxTries) {
                        queue.offer(job);
                    }
                    continue;
                }

                visited.put(urlString);
                total.incrementAndGet();

                for (final URL link : links) {
                    final String linkURL = link.toString();
                    ps.println(urlString + '\t' + linkURL);
                    if (!visited.mightContain(linkURL)) {
                        queue.offer(new Job(link));
                    }
                }
            }
        }
    }
}
