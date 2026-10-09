package net.legacylauncher.minecraft.crash;

public interface Action {
    void execute() throws Exception;

    /**
     * @return the link this action opens, if any
     */
    default String getUrl() {
        return null;
    }
}
