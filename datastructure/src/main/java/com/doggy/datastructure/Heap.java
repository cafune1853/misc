package com.doggy.datastructure;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by huangzhw on 2016/8/18.
 */
public class Heap<T extends Comparable<? super T>> {
	public enum HeapType {
		MAX, MIN
	}

    private List<T> values;
    /** 堆的整体大小 **/
	private final int maxHeapSize;
    private int currentHeapSize;
	private HeapType heapType;

	public Heap(int maxHeapSize, HeapType heapType) {
		this.maxHeapSize = maxHeapSize;
		this.heapType = heapType;
		this.currentHeapSize = 0;
        values = new ArrayList<>();
	}
	
	public T popTop(){
	    if(isEmpty()){
	        return null;
        }else{
            swap(0, currentHeapSize - 1);
            T max = values.get(currentHeapSize - 1);
            values.set(currentHeapSize - 1,null);
            currentHeapSize--;
            sinkDown(0);
            return max;
        }
    }
    
    public boolean insertOne(T val){
        if(isFull()){
            return false;
        }
        currentHeapSize++;
        values.add(val);
        flowUp(currentHeapSize - 1);
        return true;
    }
    
    public void flowUp(int index){
        while(!isRoot(index)){
            int parent = getParent(index);
            if(heapType == HeapType.MAX){
                if(values.get(index).compareTo(values.get(parent)) <= 0){
                    break;
                }
            }else{
                if(values.get(index).compareTo(values.get(parent)) >= 0){
                    break;
                }
            }
            swap(parent,index);
            index = parent;
        }
    }

	public void sinkDown(int currentIndex) {
		if (heapType == HeapType.MAX) {
            int rightIndex = getRightIndex(currentIndex);
            int leftIndex = getLeftIndex(currentIndex);
            while (leftIndex != -1){
                int nextIndex = -1;
                if(values.get(leftIndex).compareTo(values.get(currentIndex)) > 0){
                    nextIndex = leftIndex;
                }
                if(rightIndex != -1 && values.get(rightIndex).compareTo(values.get(currentIndex)) > 0){
                    nextIndex = rightIndex;
                }
                if(nextIndex == -1){
                    break;
                }
                swap(currentIndex, nextIndex);
                currentIndex = nextIndex;
                leftIndex = getLeftIndex(currentIndex);
                rightIndex = getRightIndex(currentIndex);
            }
		}else{
            int rightIndex = getRightIndex(currentIndex);
            int leftIndex = getLeftIndex(currentIndex);
            while (leftIndex != -1){
                int nextIndex = -1;
                if(values.get(leftIndex).compareTo(values.get(currentIndex)) < 0){
                    nextIndex = leftIndex;
                }
                if(rightIndex != -1 && values.get(rightIndex).compareTo(values.get(currentIndex)) < 0){
                    nextIndex = rightIndex;
                }
                if(nextIndex == -1){
                    break;
                }
                currentIndex = nextIndex;
                leftIndex = getLeftIndex(currentIndex);
                rightIndex = getRightIndex(currentIndex);
            }
        }
	}
	
	private void swap(int x,int y){
	    T tmp = values.get(x);
        values.set(x,values.get(y));
        values.set(y,tmp);
    }

	private int getLeftIndex(int root){
	    int ret = -1;
        if(hasLeftChild(root)){
            ret = root * 2 + 1;
        }
        return ret;
    }
    
    private int getRightIndex(int root){
        int ret = -1;
        if(hasRightChild(root)){
            ret = root * 2 + 2;
        }
        return ret;
    }
    
    private int getParent(int c){
        return (c + 1) / 2 - 1;
    }

	private boolean isEmpty() {
		return currentHeapSize == 0;
	}

	private boolean isFull() {
		return currentHeapSize == maxHeapSize;
	}

	private boolean hasLeftChild(int root) {
		return root * 2 + 1 <= currentHeapSize - 1;
	}

	private boolean hasRightChild(int root) {
		return root * 2 + 2 <= currentHeapSize - 1;
	}

	private boolean isRoot(int x) {
		return x == 0;
	}
    
    public static void main(String[] args) {
        Heap<String> maxHeap = new Heap<>(5, HeapType.MAX);
        maxHeap.insertOne("a");
        maxHeap.insertOne("b");
        maxHeap.insertOne("c");
        maxHeap.insertOne("d");
        maxHeap.insertOne("e");
        String x;
        while((x = maxHeap.popTop()) != null){
            System.out.println(x);
        }
    }
}
