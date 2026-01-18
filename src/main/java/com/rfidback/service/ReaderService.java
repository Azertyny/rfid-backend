package com.rfidback.service;

import java.util.ArrayList;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.rfidback.entity.ReaderEntity;
import com.rfidback.generated.model.CreateReader;
import com.rfidback.generated.model.Reader;
import com.rfidback.generated.model.ReadersList;
import com.rfidback.repository.ReaderRepository;

@Service
public class ReaderService {

    @Autowired
    ReaderRepository readerRepository;

    public Reader createReader(CreateReader createReader) throws Exception {
        // Here you would add logic to save the reader to a database
        // For demonstration, we will just create a Reader object and return it

        ReaderEntity readerEntity = ReaderEntity.builder().name(createReader.getUid()).build();
        ReaderEntity readerEntitySaved = readerRepository.save(readerEntity);
        Reader reader = new Reader();
        reader.setUid(readerEntitySaved.getName());
        reader.setApitoken(readerEntitySaved.getApitoken());
        return reader;
    }

    public ReadersList getReaders() {
        ReadersList readersList = new ReadersList();
        ArrayList<Reader> readerArrayList = new ArrayList<>();
        for (ReaderEntity readerEntity : readerRepository.findAll()) {
            Reader reader = new Reader();
            reader.setUid(readerEntity.getName());
            reader.setApitoken(readerEntity.getApitoken());
            reader.setCreationDate(readerEntity.getCreationDate());
            reader.setUpdateDate(readerEntity.getUpdateDate());
            readerArrayList.add(reader);
        }
        readersList.setReaders(readerArrayList);

        return readersList;
    }

}
