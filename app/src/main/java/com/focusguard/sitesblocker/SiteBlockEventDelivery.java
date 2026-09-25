package com.focusguard.sitesblocker;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;

import java.util.function.Consumer;

/**
 * Entrega os eventos ao SiteBlockEngine como o Android os entregava ao serviço do Bloquear Sites,
 * que declarava android:notificationTimeout="40": cada evento chega 40 ms depois de acontecer, e
 * os eventos de um mesmo tipo nesse intervalo chegam como um só, o último.
 * TYPE_WINDOW_CONTENT_CHANGED não se junta, só atrasa (é assim que o Android faz).
 *
 * O serviço do FocusGuard recebe os eventos sem atraso (notificationTimeout 0), do que dependem a
 * autoproteção e os bloqueios de apps. Por isso o atraso do Bloquear Sites é refeito aqui, só para
 * o motor, que assim recebe a mesma sequência de eventos do app original.
 */
public final class SiteBlockEventDelivery {
    /** android:notificationTimeout do serviço do Bloquear Sites. */
    public static final long NOTIFICATION_TIMEOUT_MS = 40L;

    private final Consumer<AccessibilityEvent> target;
    // No máximo um evento pendente por tipo, como no Android.
    private final SparseArray<AccessibilityEvent> pendingByType = new SparseArray<>();
    private final Handler handler = new Handler(Looper.getMainLooper(), this::deliver);

    public SiteBlockEventDelivery(Consumer<AccessibilityEvent> target) {
        this.target = target;
    }

    /** Chamado na thread principal com cada evento que o motor deve receber. */
    public void post(AccessibilityEvent event) {
        // O Android recicla o evento depois do callback do serviço: o motor recebe uma cópia.
        AccessibilityEvent copy = copyOf(event);
        int eventType = event.getEventType();
        Message message;
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            message = handler.obtainMessage(eventType, copy);
        } else {
            AccessibilityEvent previous = pendingByType.get(eventType);
            pendingByType.put(eventType, copy);
            if (previous != null) handler.removeMessages(eventType);
            message = handler.obtainMessage(eventType);
        }
        handler.sendMessageDelayed(message, NOTIFICATION_TIMEOUT_MS);
    }

    /** Descarta os eventos ainda não entregues (o serviço foi encerrado). */
    public void clear() {
        handler.removeCallbacksAndMessages(null);
        pendingByType.clear();
    }

    private boolean deliver(Message message) {
        AccessibilityEvent event = (AccessibilityEvent) message.obj;
        if (event == null) {
            event = pendingByType.get(message.what);
            if (event == null) return true;
            pendingByType.remove(message.what);
        }
        target.accept(event);
        return true;
    }

    @SuppressWarnings("deprecation")
    private static AccessibilityEvent copyOf(AccessibilityEvent event) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return new AccessibilityEvent(event);
        return AccessibilityEvent.obtain(event);
    }
}
