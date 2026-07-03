package top.eiyooooo.easycontrol.server.entity;
/**
 * 类 Pointer
 * 说明：该类负责 Pointer 相关功能。
 */

public final class Pointer {

    public int id;

    public float x;

    public float y;

    public long downTime;

    public Pointer(int id, long downTime) {
        this.id = id;
        this.downTime = downTime;
    }

}
