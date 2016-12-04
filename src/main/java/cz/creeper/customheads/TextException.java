package cz.creeper.customheads;

import org.spongepowered.api.text.Text;

public class TextException extends RuntimeException {
    private final Text text;
    private final Throwable child;

    public TextException(Text text, Throwable child) {
        this.text = text;
        this.child = child;
    }

    public Text getText() {
        return text;
    }

    public Throwable getChild() {
        return child;
    }
}
