package com.example.jarchess.online.move;

import com.example.jarchess.match.datapackage.Datapackage;

import java.util.concurrent.LinkedBlockingQueue;

public class DatapackageQueue {
    private LinkedBlockingQueue<Datapackage> inboundDatapackage;
    private LinkedBlockingQueue<Datapackage> outboundDatapackage;
    private String gameId;

    public DatapackageQueue() {
        this.inboundDatapackage = new LinkedBlockingQueue<Datapackage>();
        this.outboundDatapackage = new LinkedBlockingQueue<Datapackage>();
    }

    public void insertDatapackage(Datapackage datapackage) {
        try {
            outboundDatapackage.put(datapackage);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    public Datapackage getDatapackage() {
        try {
            return inboundDatapackage.take();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

}
