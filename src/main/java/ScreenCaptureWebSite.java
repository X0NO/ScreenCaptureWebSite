import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.interactions.Actions;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.io.*;
import java.nio.file.*;
import java.util.List; // java.awt a aussi une classe List
import java.util.concurrent.*;
import java.util.function.UnaryOperator;

int pollMs = 150;              // intervalle entre deux vérifications de la page
int changeTimeoutMs = 10_000;  // au-delà, on considère que la page ne changera plus

// Cherche, sous le centre de la zone, l'élément dont les bords collent le mieux à la zone
// (rapport surface commune / surface totale). Renvoie ses bords, ou null.
String snapScript = """
        const [x, y, w, h] = arguments;
        let best = null, bestScore = 0;
        for (let e = document.elementFromPoint(x + w / 2, y + h / 2); e && e !== document.documentElement; e = e.parentElement) {
            const r = e.getBoundingClientRect();
            const ix = Math.max(0, Math.min(x + w, r.right) - Math.max(x, r.left));
            const iy = Math.max(0, Math.min(y + h, r.bottom) - Math.max(y, r.top));
            const common = ix * iy, score = common / (w * h + r.width * r.height - common);
            if (score > bestScore) { bestScore = score; best = e; }
        }
        if (!best) return null;
        const r = best.getBoundingClientRect();
        return {element: best, frame: best.tagName === 'IFRAME', score: bestScore,
                x: r.x, y: r.y, w: r.width, h: r.height,
                innerX: r.x + best.clientLeft, innerY: r.y + best.clientTop};
        """;

record Candidate(Rectangle box, double score) {}

BufferedImage screenShot(FirefoxDriver driver) throws Exception {
    byte[] png = driver.getScreenshotAs(OutputType.BYTES);
    return ImageIO.read(new ByteArrayInputStream(png));
}

boolean samePixels(BufferedImage a, BufferedImage b) {
    if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
    int w = a.getWidth(), h = a.getHeight();
    return Arrays.equals(a.getRGB(0, 0, w, h, null, 0, w), b.getRGB(0, 0, w, h, null, 0, w));
}

// Vrai quand le document est chargé et que toutes ses images, y compris les images de fond
// (CSS background), sont téléchargées.
String loadedScript = """
        if (document.readyState !== 'complete') return false;
        for (const img of document.images) if (!img.complete) return false;
        for (const e of document.querySelectorAll('*'))
            for (const m of getComputedStyle(e).backgroundImage.matchAll(/url\\("([^"]+)"\\)/g)) {
                const img = new Image();
                img.src = m[1];
                if (!img.complete) return false;
            }
        return true;
        """;

// Vrai quand la page et le cadre qui contient le livre (s'il y en a un) ont fini de charger.
boolean pageLoaded(FirefoxDriver driver) {
    var frames = driver.findElements(By.tagName("iframe"));
    try {
        if (!Boolean.TRUE.equals(driver.executeScript(loadedScript))) return false;
        if (!frames.isEmpty()) driver.switchTo().frame(frames.getFirst());
        return Boolean.TRUE.equals(driver.executeScript(loadedScript));
    } finally {
        driver.switchTo().defaultContent();
    }
}

// Attend que la page soit chargée, puis que la zone soit différente de `previous`
// (null : n'importe quelle image) et identique sur deux captures de suite.
// Renvoie null si rien ne change à temps.
BufferedImage waitForPage(FirefoxDriver driver, Rectangle zone, BufferedImage previous) throws Exception {
    long deadline = System.currentTimeMillis() + changeTimeoutMs;
    BufferedImage last = null;
    while (System.currentTimeMillis() < deadline) {
        // Les captures prises avant la fin du chargement ne comptent pas (page encore blanche).
        // Pendant un changement de page, Firefox refuse parfois la capture : on réessaie.
        BufferedImage shot;
        try {
            shot = pageLoaded(driver) ? screenShot(driver) : null;
        } catch (WebDriverException e) {
            shot = null;
        }
        if (shot == null) {
            last = null;
            Thread.sleep(pollMs);
            continue;
        }
        BufferedImage current = zone == null ? shot : shot.getSubimage(zone.x, zone.y, zone.width, zone.height);
        boolean changed = previous == null || !samePixels(current, previous);
        if (changed && last != null && samePixels(current, last)) return current;
        last = changed ? current : null;
        Thread.sleep(pollMs);
    }
    return null;
}

// Ajuste la zone tracée sur les bords de l'élément de la page qui lui correspond le mieux
// (la fiche du livre par exemple). Garde la zone telle quelle si rien ne correspond assez.
Rectangle snapToElement(FirefoxDriver driver, Rectangle zone) {
    try {
        Candidate best = bestElement(driver, zone, 0, 0);
        return best != null && best.score() > 0.3 ? best.box() : zone;
    } finally {
        driver.switchTo().defaultContent();
    }
}

// Cherche dans la page courante, puis à l'intérieur du cadre trouvé s'il y en a un.
// offsetX/offsetY : position du cadre courant dans la capture.
Candidate bestElement(FirefoxDriver driver, Rectangle zone, int offsetX, int offsetY) {
    @SuppressWarnings("unchecked")
    var found = (Map<String, Object>) driver.executeScript(snapScript,
            zone.x - offsetX, zone.y - offsetY, zone.width, zone.height);
    if (found == null) return null;

    // Arrondi vers l'extérieur pour ne perdre aucune ligne de pixels de l'élément
    double x = num(found, "x"), y = num(found, "y");
    int left = (int) Math.floor(x), top = (int) Math.floor(y);
    var box = new Rectangle(offsetX + left, offsetY + top,
            (int) Math.ceil(x + num(found, "w")) - left, (int) Math.ceil(y + num(found, "h")) - top);
    var best = new Candidate(box, num(found, "score"));
    if (Boolean.TRUE.equals(found.get("frame"))) {
        driver.switchTo().frame((WebElement) found.get("element"));
        Candidate inside = bestElement(driver, zone,
                offsetX + (int) Math.round(num(found, "innerX")), offsetY + (int) Math.round(num(found, "innerY")));
        if (inside != null && inside.score() > best.score()) best = inside;
    }
    return best;
}

double num(Map<String, Object> values, String key) {
    return ((Number) values.get(key)).doubleValue();
}

// Si le contenu est dans un cadre (iframe), la touche doit être envoyée dedans.
// On revient ensuite à la page principale, sinon la capture d'écran échoue.
void pressKey(FirefoxDriver driver, CharSequence key) {
    var frames = driver.findElements(By.tagName("iframe"));
    if (!frames.isEmpty()) driver.switchTo().frame(frames.getFirst());
    try {
        new Actions(driver).sendKeys(key).perform();
    } finally {
        driver.switchTo().defaultContent();
    }
}

// Renvoie le lien du site, ou null si annulé.
String askUrl() {
    while (true) {
        String answer = JOptionPane.showInputDialog(null, "Lien du site à capturer :");
        if (answer == null) return null;
        answer = answer.trim();
        if (!answer.isEmpty()) return answer.contains("://") ? answer : "https://" + answer;
        JOptionPane.showMessageDialog(null, "Entrez un lien.");
    }
}

// Met chaque image sur une page du PDF, à sa taille d'origine.
void toPdf(List<Path> images, Path pdf) throws IOException {
    try (var document = new PDDocument()) {
        for (Path image : images) {
            var picture = PDImageXObject.createFromFile(image.toString(), document);
            var page = new PDPage(new PDRectangle(picture.getWidth(), picture.getHeight()));
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.drawImage(picture, 0, 0);
            }
        }
        document.save(pdf.toFile());
    }
}

// Renvoie le nombre de captures voulu, ou 0 si annulé.
int askPageCount() {
    while (true) {
        String answer = JOptionPane.showInputDialog(null, "Combien de captures faire ?", "1");
        if (answer == null) return 0;
        try {
            int count = Integer.parseInt(answer.trim());
            if (count >= 1) return count;
        } catch (NumberFormatException _) {
        }
        JOptionPane.showMessageDialog(null, "Entrez un nombre entier supérieur à 0.");
    }
}

// Attend l'appui sur une touche et la renvoie au format Selenium, ou null si annulé (Échap).
CharSequence askNextPageKey() throws Exception {
    CharSequence[] result = new CharSequence[1];

    SwingUtilities.invokeAndWait(() -> {
        var dialog = new JDialog((Window) null, "Page suivante", Dialog.ModalityType.APPLICATION_MODAL);
        var label = new JLabel("Appuyez sur la touche qui passe à la page suivante (Échap pour annuler)");
        label.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        label.setFocusable(true);
        label.setFocusTraversalKeysEnabled(false); // pour pouvoir choisir Tab
        label.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    dialog.dispose();
                    return;
                }
                CharSequence key = toSeleniumKey(e);
                if (key == null) return; // touche non gérée (Maj, Ctrl...) : on attend la suivante
                IO.println("Touche page suivante : " + KeyEvent.getKeyText(e.getKeyCode()));
                result[0] = key;
                dialog.dispose();
            }
        });

        dialog.add(label);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        label.requestFocusInWindow();
        dialog.setVisible(true); // bloque jusqu'à l'appui
    });

    return result[0];
}

CharSequence toSeleniumKey(KeyEvent e) {
    return switch (e.getKeyCode()) {
        case KeyEvent.VK_RIGHT -> Keys.ARROW_RIGHT;
        case KeyEvent.VK_LEFT -> Keys.ARROW_LEFT;
        case KeyEvent.VK_DOWN -> Keys.ARROW_DOWN;
        case KeyEvent.VK_UP -> Keys.ARROW_UP;
        case KeyEvent.VK_PAGE_DOWN -> Keys.PAGE_DOWN;
        case KeyEvent.VK_PAGE_UP -> Keys.PAGE_UP;
        case KeyEvent.VK_SPACE -> Keys.SPACE;
        case KeyEvent.VK_ENTER -> Keys.ENTER;
        case KeyEvent.VK_TAB -> Keys.TAB;
        case KeyEvent.VK_HOME -> Keys.HOME;
        case KeyEvent.VK_END -> Keys.END;
        default -> {
            char c = e.getKeyChar();
            yield c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c) ? String.valueOf(c) : null;
        }
    };
}

// Affiche l'image dans une fenêtre : on trace la zone à la souris, elle s'ajuste avec `snap`
// au relâchement (sauf si Ctrl est enfoncé), Entrée valide, Échap annule.
// Renvoie la zone en pixels de l'image, ou null si annulé.
Rectangle selectZone(BufferedImage image, UnaryOperator<Rectangle> snap) throws Exception {
    Rectangle[] result = new Rectangle[1];

    SwingUtilities.invokeAndWait(() -> {
        // Réduit l'image si elle est plus grande que l'écran
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        double scale = Math.min(1.0, Math.min(screen.width * 0.95 / image.getWidth(), screen.height * 0.9 / image.getHeight()));
        int w = (int) (image.getWidth() * scale), h = (int) (image.getHeight() * scale);
        Rectangle imageBounds = new Rectangle(0, 0, image.getWidth(), image.getHeight());

        var dialog = new JDialog((Window) null,
                "Tracez la zone (elle s'ajuste à la fiche, Ctrl pour garder votre tracé), puis Entrée (Échap pour annuler)",
                Dialog.ModalityType.APPLICATION_MODAL);
        Point[] drag = new Point[2]; // début et fin du glisser, en pixels de la fenêtre

        var panel = new JPanel() {
            Rectangle selection() {
                if (drag[0] == null || drag[1] == null) return null;
                return new Rectangle(Math.min(drag[0].x, drag[1].x), Math.min(drag[0].y, drag[1].y),
                        Math.abs(drag[0].x - drag[1].x), Math.abs(drag[0].y - drag[1].y));
            }

            // Repasse des pixels de la fenêtre aux pixels de l'image
            Rectangle selectionInImage() {
                Rectangle sel = selection();
                if (sel == null) return null;
                Rectangle zone = new Rectangle((int) (sel.x / scale), (int) (sel.y / scale),
                        (int) Math.round(sel.width / scale), (int) Math.round(sel.height / scale))
                        .intersection(imageBounds);
                return zone.width < 1 || zone.height < 1 ? null : zone;
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.drawImage(image, 0, 0, w, h, null);
                Rectangle sel = selection();
                if (sel != null) {
                    var g2 = (Graphics2D) g;
                    g2.setColor(new Color(0, 120, 215, 60));
                    g2.fill(sel);
                    g2.setColor(new Color(0, 120, 215));
                    g2.setStroke(new BasicStroke(2));
                    g2.draw(sel);
                }
            }
        };
        panel.setPreferredSize(new Dimension(w, h));
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        var mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                drag[0] = e.getPoint();
                drag[1] = e.getPoint();
                panel.repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                drag[1] = e.getPoint();
                panel.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                Rectangle zone = panel.selectionInImage();
                if (zone == null || e.isControlDown()) return;
                Rectangle snapped = snap.apply(zone).intersection(imageBounds);
                if (snapped.width < 1 || snapped.height < 1) return;
                drag[0] = new Point((int) Math.round(snapped.x * scale), (int) Math.round(snapped.y * scale));
                drag[1] = new Point((int) Math.round(snapped.getMaxX() * scale), (int) Math.round(snapped.getMaxY() * scale));
                panel.repaint();
            }
        };
        panel.addMouseListener(mouse);
        panel.addMouseMotionListener(mouse);

        JRootPane root = dialog.getRootPane();
        root.registerKeyboardAction(e -> {
            Rectangle zone = panel.selectionInImage();
            if (zone == null) return;
            result[0] = zone;
            dialog.dispose();
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.add(panel);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true); // bloque jusqu'à Entrée ou Échap
    });

    return result[0];
}

void main() throws Exception {
    String url = askUrl();
    if (url == null) {
        IO.println("Annulé");
        return;
    }

    int width = 3440, height = 1440;
    var options = new FirefoxOptions();
    options.addArguments("-headless", "--width=" + width, "--height=" + height);

    // Firefox démarre et charge le site pendant qu'on répond aux questions suivantes
    CompletableFuture<FirefoxDriver> browser = CompletableFuture.supplyAsync(() -> {
        var driver = new FirefoxDriver(options);
        driver.get(url);
        return driver;
    });

    int pageCount = askPageCount();
    CharSequence nextPageKey = pageCount == 0 ? null : askNextPageKey();
    if (nextPageKey == null) {
        IO.println("Annulé");
        browser.handle((driver, error) -> {
            if (driver != null) driver.quit();
            return null;
        }).join();
        return;
    }

    var driver = browser.join();
    // Les images sont enregistrées en arrière-plan pendant qu'on passe à la page suivante
    try (var saver = Executors.newSingleThreadExecutor()) {
        BufferedImage first = waitForPage(driver, null, null);
        if (first == null) first = screenShot(driver);

        // La zone choisie sur la première page sert pour toutes les pages
        Rectangle zone = selectZone(first, z -> snapToElement(driver, z));
        if (zone == null) {
            IO.println("Sélection annulée");
            return;
        }

        Path folder = Path.of("captures");
        Files.createDirectories(folder);
        var files = new ArrayList<Path>();
        var saves = new ArrayList<Future<?>>();

        BufferedImage page = screenShot(driver).getSubimage(zone.x, zone.y, zone.width, zone.height);
        for (int i = 1; i <= pageCount; i++) {
            Path file = folder.resolve("page-%03d.png".formatted(i));
            BufferedImage image = page;
            saves.add(saver.submit(() -> ImageIO.write(image, "png", file.toFile())));
            files.add(file);
            IO.println("Capture " + i + "/" + pageCount + " : " + file);

            if (i < pageCount) {
                pressKey(driver, nextPageKey);
                page = waitForPage(driver, zone, page);
                if (page == null) {
                    IO.println("La page ne change plus (dernière page ?) : arrêt après " + i + " captures");
                    break;
                }
            }
        }
        for (var save : saves) save.get(); // remonte une éventuelle erreur d'écriture

        // Le PDF porte le titre de la page (sans les caractères interdits dans un nom de fichier)
        String name = driver.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        Path pdf = folder.resolve((name.isEmpty() ? "captures" : name) + ".pdf");
        toPdf(files, pdf);
        IO.println("PDF créé : " + pdf.toAbsolutePath());
    } finally{
        driver.quit();
    }
}
