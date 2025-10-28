package com.rfidback.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.rfidback.generated.api.ReaderApiDelegate;
import com.rfidback.generated.model.CreateReader;
import com.rfidback.generated.model.Reader;
import com.rfidback.generated.model.ReadersList;
import com.rfidback.service.ReaderService;

@Service
public class ReaderController implements ReaderApiDelegate {

    @Autowired
    private ReaderService readerService;

    @Override
    public ResponseEntity<Reader> createReader(CreateReader createReader) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED).body(this.readerService.createReader(createReader));
    }

    @Override
    public ResponseEntity<ReadersList> listReaders() throws Exception {

        return ResponseEntity.ok(this.readerService.getReaders());
    }

}
