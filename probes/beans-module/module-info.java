/*
 * Minimal java.desktop descriptor for the gdcc JavaBeans-only runtime.
 *
 * The JDK's java.desktop module bundles AWT/Swing/ImageIO/Sound/Print plus
 * JavaBeans. gdcc only needs JavaBeans (FreeMarker's introspection) and a few
 * Swing tree types that FreeMarker core references in signatures/interfaces.
 *
 * This descriptor keeps the java.desktop name on purpose: java.base grants the
 * qualified exports jdk.internal.access and sun.reflect.generics.reflectiveObjects
 * to java.desktop, and jdk.internal.module.ModuleLoaderMap maps java.desktop to a
 * built-in loader. A differently named module would be assigned to the application
 * loader, which is forbidden from defining java.* packages.
 *
 * No GUI native libraries, headers, man pages, or services are declared or shipped.
 */
module java.desktop {
    requires java.base;

    exports java.beans;
    exports java.beans.beancontext;
    exports javax.swing.tree;
}
